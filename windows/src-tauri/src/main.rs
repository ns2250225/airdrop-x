#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use std::{
    collections::HashMap,
    io::Write,
    net::{TcpListener, TcpStream},
    path::Path,
    sync::{Arc, Mutex, mpsc},
    time::Duration,
};
use windows::{
    Devices::{
        Bluetooth::{
            Advertisement::{
                BluetoothLEAdvertisementPublisher, BluetoothLEAdvertisementReceivedEventArgs,
                BluetoothLEAdvertisementWatcher, BluetoothLEManufacturerData,
                BluetoothLEScanningMode,
            },
            BluetoothLEDevice,
        },
        Enumeration::DeviceInformation,
        WiFiDirect::{
            WiFiDirectConfigurationMethod, WiFiDirectConnectionParameters, WiFiDirectDevice,
            WiFiDirectDeviceSelectorType, WiFiDirectPairingProcedure,
        },
    },
    Foundation::TypedEventHandler,
    Storage::Streams::DataWriter,
};

const COMPANY_ID: u16 = 0xfffe;

#[derive(Serialize, Clone)]
struct NearbyDevice {
    name: String,
    address: String,
    rssi: i16,
}

struct RadioState {
    _publisher: Option<BluetoothLEAdvertisementPublisher>,
    wifi: Mutex<Option<WiFiDirectDevice>>,
}

fn start_ble_advertising() -> windows::core::Result<BluetoothLEAdvertisementPublisher> {
    let publisher = BluetoothLEAdvertisementPublisher::new()?;
    let writer = DataWriter::new()?;
    writer.WriteBytes(b"ADX1")?;
    let data = BluetoothLEManufacturerData::new()?;
    data.SetCompanyId(COMPANY_ID)?;
    data.SetData(&writer.DetachBuffer()?)?;
    publisher
        .Advertisement()?
        .ManufacturerData()?
        .Append(&data)?;
    publisher.Start()?;
    Ok(publisher)
}

#[tauri::command]
async fn scan_ble() -> Result<Vec<NearbyDevice>, String> {
    tauri::async_runtime::spawn_blocking(|| {
        let devices = Arc::new(Mutex::new(HashMap::new()));
        let output = devices.clone();
        let watcher = BluetoothLEAdvertisementWatcher::new().map_err(|error| error.to_string())?;
        watcher
            .SetScanningMode(BluetoothLEScanningMode::Active)
            .map_err(|error| error.to_string())?;
        let token = watcher
            .Received(&TypedEventHandler::<
                BluetoothLEAdvertisementWatcher,
                BluetoothLEAdvertisementReceivedEventArgs,
            >::new(move |_, args| {
                let args = args.ok()?;
                let advertisement = args.Advertisement()?;
                if advertisement
                    .GetManufacturerDataByCompanyId(COMPANY_ID)?
                    .Size()?
                    == 0
                {
                    return Ok(());
                }
                let address = args.BluetoothAddress()?;
                let name = advertisement.LocalName()?.to_string();
                output.lock().unwrap().insert(
                    address,
                    NearbyDevice {
                        name: if name.is_empty() {
                            "AirDrop-X Android".into()
                        } else {
                            name
                        },
                        address: format!("{address:012X}"),
                        rssi: args.RawSignalStrengthInDBm()?,
                    },
                );
                Ok(())
            }))
            .map_err(|error| error.to_string())?;
        watcher.Start().map_err(|error| error.to_string())?;
        std::thread::sleep(Duration::from_secs(4));
        watcher.Stop().map_err(|error| error.to_string())?;
        watcher
            .RemoveReceived(token)
            .map_err(|error| error.to_string())?;
        let mut result: Vec<_> = devices.lock().unwrap().values().cloned().collect();
        for device in &mut result {
            if device.name == "AirDrop-X Android" {
                let address = u64::from_str_radix(&device.address, 16)
                    .map_err(|error| format!("无效的 BLE 地址 {}：{error}", device.address))?;
                if let Ok(name) = BluetoothLEDevice::FromBluetoothAddressAsync(address)
                    .and_then(|operation| operation.get())
                    .and_then(|bluetooth| bluetooth.Name())
                {
                    let name = name.to_string();
                    if !name.trim().is_empty() {
                        device.name = name;
                    }
                }
            }
        }
        Ok(result)
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn connect_wifi_direct(
    target_name: String,
    target_address: String,
    app: tauri::AppHandle,
    state: tauri::State<'_, RadioState>,
) -> Result<String, String> {
    let id = tauri::async_runtime::spawn_blocking(move || {
        let selector =
            WiFiDirectDevice::GetDeviceSelector2(WiFiDirectDeviceSelectorType::AssociationEndpoint)
                .map_err(|error| error.to_string())?;
        let mut available = Vec::new();
        let expected = normalized_device_name(&target_name);
        let mut matched_id = None;
        for attempt in 0..16 {
            let devices = DeviceInformation::FindAllAsyncAqsFilter(&selector)
                .and_then(|operation| operation.get())
                .map_err(|error| error.to_string())?;
            available.clear();
            let mut matches = Vec::new();
            for index in 0..devices.Size().map_err(|error| error.to_string())? {
                let item = devices.GetAt(index).map_err(|error| error.to_string())?;
                let name = item.Name().map_err(|error| error.to_string())?.to_string();
                let candidate = normalized_device_name(&name);
                available.push(name);
                if !candidate.is_empty()
                    && (candidate == expected
                        || candidate.ends_with(&expected)
                        || expected.ends_with(&candidate))
                {
                    matches.push(item);
                }
            }
            match matches.as_slice() {
                [id] => {
                    matched_id = Some(id.clone());
                    break;
                }
                [] if attempt < 15 => std::thread::sleep(Duration::from_millis(500)),
                [] => {}
                _ => {
                    return Err(format!(
                        "发现多个与“{target_name}”匹配的 Wi-Fi Direct 端点，无法唯一识别所选设备（{target_address}）"
                    ));
                }
            }
        }
        let matched = matched_id.ok_or_else(|| {
            format!(
                "等待 8 秒后仍未找到与所选 BLE 设备“{target_name}”（{target_address}）匹配的 Wi-Fi Direct 端点；当前端点：{}",
                available.join("、")
            )
        })?;
        matched
            .Id()
            .map_err(|error| format!("读取目标 Wi-Fi Direct 设备 ID 失败：{error}"))
    })
    .await
    .map_err(|error| error.to_string())??;
    let (sender, receiver) = mpsc::sync_channel(1);
    app.run_on_main_thread(move || {
        let result = (|| {
            let parameters = WiFiDirectConnectionParameters::new()
                .map_err(|error| format!("创建 Wi-Fi Direct 连接参数失败：{error}"))?;
            parameters
                .SetGroupOwnerIntent(0)
                .map_err(|error| format!("设置 Windows 为 Wi-Fi Direct 客户端失败：{error}"))?;
            parameters
                .SetPreferredPairingProcedure(WiFiDirectPairingProcedure::Invitation)
                .map_err(|error| format!("设置 Wi-Fi Direct 邀请模式失败：{error}"))?;
            parameters
                .PreferenceOrderedConfigurationMethods()
                .and_then(|methods| methods.Append(WiFiDirectConfigurationMethod::PushButton))
                .map_err(|error| format!("设置 Wi-Fi Direct 配置方式失败：{error}"))?;
            WiFiDirectDevice::FromIdAsync2(&id, &parameters)
                .map_err(|error| format!("在 UI 线程启动 Wi-Fi Direct 连接失败：{error}"))
        })();
        let _ = sender.send(result);
    })
    .map_err(|error| format!("无法切换到 UI 线程建立 Wi-Fi Direct 连接：{error}"))?;
    let operation = receiver
        .recv()
        .map_err(|error| format!("未收到 Wi-Fi Direct 连接操作：{error}"))??;
    let device = tauri::async_runtime::spawn_blocking(move || operation.get())
        .await
        .map_err(|error| error.to_string())?
        .map_err(|error| format!("建立 Wi-Fi Direct 连接失败：{error}"))?;
    let pairs = device
        .GetConnectionEndpointPairs()
        .map_err(|error| error.to_string())?;
    let address = pairs
        .GetAt(0)
        .and_then(|pair| pair.RemoteHostName())
        .and_then(|host| host.CanonicalName())
        .map_err(|error| error.to_string())?
        .to_string();
    let endpoint = format!("{address}:48765");
    for attempt in 0..10 {
        match TcpStream::connect(&endpoint).and_then(|mut stream| stream.write_all(b"ADXP")) {
            Ok(()) => break,
            Err(error) if attempt == 9 => {
                return Err(format!("直连成功，但 Android 接收端未就绪：{error}"));
            }
            Err(_) => std::thread::sleep(Duration::from_millis(300)),
        }
    }
    *state.wifi.lock().unwrap() = Some(device);
    Ok(endpoint)
}

fn normalized_device_name(name: &str) -> String {
    name.chars()
        .filter(|character| !character.is_whitespace())
        .flat_map(char::to_lowercase)
        .collect()
}

#[tauri::command]
async fn send_file(address: String, path: String) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let stream = TcpStream::connect(address).map_err(|error| error.to_string())?;
        airdrop_x_protocol::send(stream, Path::new(&path)).map_err(|error| error.to_string())
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn receive_file(bind: String, directory: String) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let listener = TcpListener::bind(bind).map_err(|error| error.to_string())?;
        let (stream, _) = listener.accept().map_err(|error| error.to_string())?;
        airdrop_x_protocol::receive(stream, Path::new(&directory))
            .map(|path| path.display().to_string())
            .map_err(|error| error.to_string())
    })
    .await
    .map_err(|error| error.to_string())?
}

fn main() {
    let publisher = start_ble_advertising().ok();
    tauri::Builder::default()
        .manage(RadioState {
            _publisher: publisher,
            wifi: Mutex::new(None),
        })
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            scan_ble,
            connect_wifi_direct,
            send_file,
            receive_file
        ])
        .run(tauri::generate_context!())
        .expect("failed to run AirDrop-X");
}
