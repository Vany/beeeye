use std::net::UdpSocket;

use anyhow::{Context, Result};
use nalgebra::UnitQuaternion;
use rosc::{OscMessage, OscPacket, OscType};

pub struct OscSender {
    socket: UdpSocket,
    target: String,
}

impl OscSender {
    pub fn new(host: &str, port: u16) -> Result<Self> {
        let socket = UdpSocket::bind("0.0.0.0:0").context("failed to bind UDP socket")?;
        let target = format!("{host}:{port}");
        Ok(Self { socket, target })
    }

    pub fn send_quaternion(&self, q: &UnitQuaternion<f64>) -> Result<()> {
        // ar-drivers RUB: X=Right, Y=Up, Z=Back
        // Face tracker reference: positive gyro_y → ft_qy decreases → negate y
        let components = [
            ("/data/faceTracking/face/rotation/x", -q.i as f32),
            ("/data/faceTracking/face/rotation/y", -q.j as f32),
            ("/data/faceTracking/face/rotation/z", -q.k as f32),
            ("/data/faceTracking/face/rotation/w", q.w as f32),
        ];

        for (addr, val) in components {
            let msg = OscPacket::Message(OscMessage {
                addr: addr.to_string(),
                args: vec![OscType::Float(val)],
            });
            let bytes = rosc::encoder::encode(&msg).context("failed to encode OSC")?;
            self.socket
                .send_to(&bytes, &self.target)
                .context("failed to send OSC")?;
        }

        Ok(())
    }
}
