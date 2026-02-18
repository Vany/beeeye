use std::net::UdpSocket;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use rosc::{OscPacket, OscType};

/// Quaternion buffer — collects x,y,z,w and fires on w
#[derive(Default)]
struct QBuf {
    x: f32,
    y: f32,
    z: f32,
}

/// Latest face tracker quaternion, shared with main thread
pub struct FaceTracker {
    latest: Arc<Mutex<Option<[f32; 4]>>>,
}

impl FaceTracker {
    /// Start listening on `port` for OSC face tracker data.
    /// Returns immediately, spawns daemon thread.
    pub fn start(port: u16) -> Self {
        let latest = Arc::new(Mutex::new(None));
        let l = latest.clone();

        thread::Builder::new()
            .name("facetracker-osc".into())
            .spawn(move || {
                let socket = match UdpSocket::bind(format!("0.0.0.0:{port}")) {
                    Ok(s) => s,
                    Err(e) => {
                        eprintln!("facetracker: failed to bind :{port}: {e}");
                        return;
                    }
                };
                eprintln!("facetracker: listening on :{port}");
                socket
                    .set_read_timeout(Some(Duration::from_secs(10)))
                    .ok();

                let mut buf = [0u8; 4096];
                let mut qbuf = QBuf::default();
                let mut last_recv = Instant::now();

                loop {
                    let size = match socket.recv(&mut buf) {
                        Ok(n) => {
                            last_recv = Instant::now();
                            n
                        }
                        Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {
                            eprintln!("facetracker: no messages on :{port} for {:.0}s — is the face tracker running?", last_recv.elapsed().as_secs_f64());
                            continue;
                        }
                        Err(_) => continue,
                    };
                    let packet = match rosc::decoder::decode_udp(&buf[..size]) {
                        Ok((_, p)) => p,
                        Err(_) => continue,
                    };
                    process_packet(&packet, &mut qbuf, &l);
                }
            })
            .expect("spawn facetracker thread");

        Self { latest }
    }

    /// Take the latest quaternion (returns None if no new data since last take)
    pub fn take(&self) -> Option<[f32; 4]> {
        self.latest.lock().unwrap().take()
    }
}

fn process_packet(packet: &OscPacket, qbuf: &mut QBuf, latest: &Arc<Mutex<Option<[f32; 4]>>>) {
    match packet {
        OscPacket::Message(msg) => {
            let val = match msg.args.first() {
                Some(OscType::Float(f)) => *f,
                _ => return,
            };
            match msg.addr.as_str() {
                "/data/faceTracking/face/rotation/x" => qbuf.x = val,
                "/data/faceTracking/face/rotation/y" => qbuf.y = val,
                "/data/faceTracking/face/rotation/z" => qbuf.z = val,
                "/data/faceTracking/face/rotation/w" => {
                    *latest.lock().unwrap() = Some([qbuf.x, qbuf.y, qbuf.z, val]);
                }
                _ => {}
            }
        }
        OscPacket::Bundle(bundle) => {
            for p in &bundle.content {
                process_packet(p, qbuf, latest);
            }
        }
    }
}
