//! Checks `jam` against a real MQTT broker (`23` §4). One process per participant, because a
//! process is in one Jam at a time:
//!
//! ```text
//! cargo run --example jam_probe -- host  broker.hivemq.com 8883 CODE   # publishes a queue, prints commands
//! cargo run --example jam_probe -- guest broker.hivemq.com 8883 CODE   # prints the state, asks for Next
//! cargo run --example jam_probe -- sniff broker.hivemq.com 8883        # what anyone on the broker sees
//! ```

use core_engine::jam::{self, session, Command, Event, State};
use std::time::{Duration, Instant};

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (role, host, port) = (
        args[0].as_str(),
        args[1].clone(),
        args[2].parse::<u16>().unwrap(),
    );
    if role == "sniff" {
        return sniff(&host, port).await;
    }
    let code = args.get(3).cloned().unwrap_or_else(jam::new_code);
    println!(
        "{role}: room {code}, topic {}",
        jam::Room::new(&jam::normalize(&code).unwrap()).topic
    );
    let started = Instant::now();
    let cfg = session::Config {
        broker_host: host,
        broker_port: port,
        tls: port == 8883,
        code,
        id: format!("{role}-{}", std::process::id()),
        name: role.to_string(),
        host: role == "host",
    };
    session::join(cfg).await.expect("join");
    println!("{role}: connected in {} ms", started.elapsed().as_millis());

    if role == "host" {
        let state = State {
            // As big as a real queue: 200 songs of about 1 KB each, well past rumqttc's 10 KiB default.
            tracks: (0..200)
                .map(|i| {
                    serde_json::json!({
                        "id": format!("spotify:track:{i}"),
                        "name": format!("Song {i}"),
                        "album": {"images": [{"url": "x".repeat(900)}]},
                    })
                })
                .collect(),
            index: 0,
            playing: true,
            position_ms: 12_000,
            at_ms: 0,
            revision: 1,
        };
        session::publish_state(&state).await.expect("state");
        println!("host: state published");
    } else {
        // Asks once the state has arrived, so the host is certainly listening.
        let mut asked = false;
        let deadline = Instant::now() + Duration::from_secs(20);
        while Instant::now() < deadline {
            for e in session::poll(1_000).await {
                println!(
                    "guest: {}",
                    serde_json::to_string(&e)
                        .unwrap()
                        .chars()
                        .take(160)
                        .collect::<String>()
                );
                if matches!(e, Event::State { .. }) && !asked {
                    println!("guest: state after {} ms", started.elapsed().as_millis());
                    session::send(Command::Next).await.expect("send");
                    asked = true;
                }
            }
        }
        session::leave().await;
        return;
    }
    let deadline = Instant::now() + Duration::from_secs(25);
    while Instant::now() < deadline {
        for e in session::poll(1_000).await {
            println!(
                "host: {}",
                serde_json::to_string(&e)
                    .unwrap()
                    .chars()
                    .take(160)
                    .collect::<String>()
            );
        }
    }
    session::leave().await;
}

async fn sniff(host: &str, port: u16) {
    let mut opts = rumqttc::MqttOptions::new(format!("sniff-{}", std::process::id()), host, port);
    opts.set_keep_alive(Duration::from_secs(30));
    let (client, mut eventloop) = rumqttc::AsyncClient::new(opts, 64);
    client
        .subscribe("rustify/jam/v1/#", rumqttc::QoS::AtMostOnce)
        .await
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if let Ok(Ok(rumqttc::Event::Incoming(rumqttc::Packet::Publish(p)))) =
            tokio::time::timeout(Duration::from_secs(1), eventloop.poll()).await
        {
            let text = String::from_utf8_lossy(&p.payload);
            let readable = text.contains("Usseewa") || text.contains("spotify:track");
            println!(
                "sniff: {} ({} bytes, readable: {readable})",
                p.topic,
                p.payload.len()
            );
        }
    }
}
