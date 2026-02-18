use std::net::UdpSocket;

use rosc::OscPacket;

fn main() {
    let socket = UdpSocket::bind("127.0.0.1:8001").expect("failed to bind to 127.0.0.1:8001");
    eprintln!("listening on 127.0.0.1:8001");

    let mut buf = [0u8; 1024];
    loop {
        let (size, addr) = socket.recv_from(&mut buf).expect("recv failed");
        match rosc::decoder::decode_udp(&buf[..size]) {
            Ok((_, packet)) => print_packet(&packet, addr),
            Err(e) => eprintln!("decode error from {addr}: {e}"),
        }
    }
}

fn print_packet(packet: &OscPacket, addr: std::net::SocketAddr) {
    match packet {
        OscPacket::Message(msg) => {
            println!("{addr} {} {:?}", msg.addr, msg.args);
        }
        OscPacket::Bundle(bundle) => {
            for p in &bundle.content {
                print_packet(p, addr);
            }
        }
    }
}
