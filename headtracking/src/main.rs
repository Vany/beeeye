mod fusion;
mod logger;
mod osc;

use std::fs::File;
use std::io::{BufWriter, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;

use anyhow::Result;
use ar_drivers::{DisplayMode, GlassesEvent};
use clap::Parser;

#[derive(Parser)]
#[command(about = "Xreal head tracker → OSC for Beeeye")]
struct Args {
    /// OSC target host
    #[arg(long, default_value = "127.0.0.1")]
    host: String,

    /// OSC target port
    #[arg(long, default_value_t = 8001)]
    port: u16,

    /// Face tracker OSC listen port (0 to disable)
    #[arg(long, default_value_t = 8002)]
    ft_port: u16,

    /// Log CSV file path (empty to disable)
    #[arg(long, default_value = "")]
    log: String,
}

/// Ensure glasses are in SBS stereo mode. Switch from any non-stereo mode.
fn switch_to_stereo(glasses: &mut Box<dyn ar_drivers::ARGlasses>) {
    match glasses.get_display_mode() {
        Ok(DisplayMode::Stereo | DisplayMode::HighRefreshRateSBS) => {
            eprintln!("display: already in stereo mode");
        }
        Ok(mode) => {
            eprintln!("display: in {mode:?} mode, switching to SBS stereo");
            if let Err(e) = glasses.set_display_mode(DisplayMode::Stereo) {
                eprintln!("display: failed to switch: {e}");
            } else {
                eprintln!("display: switched to SBS stereo");
            }
        }
        Err(e) => eprintln!("display: failed to get mode: {e}"),
    }
}

fn main() -> Result<()> {
    let args = Args::parse();

    eprintln!("waiting for glasses...");
    let mut glasses = loop {
        match ar_drivers::any_glasses() {
            Ok(g) => break g,
            Err(_) => std::thread::sleep(std::time::Duration::from_secs(2)),
        }
    };
    eprintln!("connected to {}", glasses.name());

    let osc_sender = osc::OscSender::new(&args.host, args.port)?;
    eprintln!("sending OSC to {}:{}", args.host, args.port);

    // Face tracker listener
    let ft = if args.ft_port > 0 {
        Some(logger::FaceTracker::start(args.ft_port))
    } else {
        None
    };

    let running = Arc::new(AtomicBool::new(true));
    let r = running.clone();
    ctrlc::set_handler(move || {
        r.store(false, Ordering::SeqCst);
    })?;

    // Switch to SBS stereo on startup
    switch_to_stereo(&mut glasses);

    // CSV logger (downsampled to ~50 Hz)
    let mut csv: Option<BufWriter<File>> = if !args.log.is_empty() {
        let f = File::create(&args.log)?;
        let mut w = BufWriter::new(f);
        writeln!(
            w,
            "t,dx,dy,dz,dw,abs_x,abs_y,abs_z,abs_w,ft_x,ft_y,ft_z,ft_w,gx,gy,gz,acx,acy,acz"
        )?;
        eprintln!("logging to {}", args.log);
        Some(w)
    } else {
        None
    };

    let mut fuser = fusion::Fusion::new();
    let mut count: u64 = 0;
    let t0 = Instant::now();
    let mut last_ft = [0.0f32; 4];
    let mut last_log = Instant::now();

    while running.load(Ordering::Relaxed) {
        match glasses.read_event() {
            Ok(GlassesEvent::AccGyro {
                accelerometer,
                gyroscope,
                ..
            }) => {
                let gyro: [f32; 3] = gyroscope.into();
                let accel: [f32; 3] = accelerometer.into();

                // Poll face tracker for calibration
                let mut ft_update = None;
                if let Some(ref ft) = ft {
                    if let Some(fq) = ft.take() {
                        last_ft = fq;
                        ft_update = Some(fq);
                    }
                }

                let out = fuser.update(gyro, accel, ft_update);
                if let Err(e) = osc_sender.send_quaternion(&out.delta) {
                    eprintln!("OSC send error: {e}");
                }

                count += 1;

                // CSV: every 20th sample (~50 Hz)
                if let Some(ref mut w) = csv {
                    if count % 20 == 0 {
                        let t = t0.elapsed().as_secs_f64();
                        let _ = writeln!(w,
                            "{t:.4},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.6},{:.4},{:.4},{:.4},{:.4},{:.4},{:.4}",
                            out.delta.i, out.delta.j, out.delta.k, out.delta.w,
                            out.absolute.i, out.absolute.j, out.absolute.k, out.absolute.w,
                            last_ft[0], last_ft[1], last_ft[2], last_ft[3],
                            gyro[0], gyro[1], gyro[2],
                            accel[0], accel[1], accel[2],
                        );
                    }
                }
                if last_log.elapsed().as_secs() >= 10 {
                    let t = t0.elapsed().as_secs_f64();
                    eprintln!(
                        "[{t:.0}s/{count}] delta=({:.3},{:.3},{:.3},{:.3}) abs=({:.3},{:.3},{:.3},{:.3}) ft=({:.3},{:.3},{:.3},{:.3})",
                        out.delta.i, out.delta.j, out.delta.k, out.delta.w,
                        out.absolute.i, out.absolute.j, out.absolute.k, out.absolute.w,
                        last_ft[0], last_ft[1], last_ft[2], last_ft[3],
                    );
                    last_log = Instant::now();
                }
            }
            Ok(GlassesEvent::ProximityNear) => {
                eprintln!("proximity: glasses put on — ensuring SBS stereo");
                switch_to_stereo(&mut glasses);
            }
            Ok(GlassesEvent::Magnetometer { .. }) => {}
            Ok(_) => {}
            Err(e) => {
                eprintln!("read error: {e}, retrying...");
            }
        }
    }

    eprintln!("shutting down ({count} samples processed)");
    Ok(())
}
