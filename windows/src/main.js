import { invoke } from '@tauri-apps/api/core'
import { open } from '@tauri-apps/plugin-dialog'

const $ = (selector) => document.querySelector(selector)
const status = (message, failed = false) => {
  $('#status').textContent = message
  $('#status').classList.toggle('error', failed)
}

$('#scan').onclick = async () => {
  status('正在通过 BLE 扫描…')
  try {
    const devices = await invoke('scan_ble')
    const list = $('#devices')
    list.replaceChildren()
    if (!devices.length) list.textContent = '未发现 AirDrop-X Android 设备'
    for (const { name, address, rssi } of devices) {
      const label = document.createElement('label')
      label.className = 'device'
      const radio = document.createElement('input')
      radio.type = 'radio'
      radio.name = 'device'
      radio.value = address
      radio.dataset.deviceName = name
      const signal = document.createElement('small')
      signal.textContent = `${rssi} dBm`
      label.append(radio, document.createTextNode(name), signal)
      list.append(label)
    }
    $('#connect').disabled = !devices.length
    status(devices.length ? `发现 ${devices.length} 台设备` : '未发现设备')
  } catch (error) {
    status(`BLE 扫描失败：${error}`, true)
  }
}

$('#connect').onclick = async () => {
  const selected = document.querySelector('input[name="device"]:checked')
  if (!selected) {
    status('请先选择要连接的 Android 设备', true)
    return
  }
  status('正在建立 Wi‑Fi Direct 连接…')
  try {
    const address = await invoke('connect_wifi_direct', {
      targetName: selected.dataset.deviceName,
      targetAddress: selected.value,
    })
    $('#address').value = address
    $('#send').disabled = false
    status(`已直连：${address}`)
  } catch (error) {
    status(`直连失败：${error}`, true)
  }
}

$('#send').onclick = async () => {
  const path = await open({ multiple: false, directory: false })
  if (!path) return
  status('正在发送…')
  try {
    await invoke('send_file', { address: $('#address').value, path })
    status('发送完成')
  } catch (error) {
    status(`发送失败：${error}`, true)
  }
}

$('#receive').onclick = async () => {
  const directory = await open({ directory: true })
  if (!directory) return
  status('等待设备连接…')
  try {
    const path = await invoke('receive_file', { bind: $('#bind').value, directory })
    status(`已接收：${path}`)
  } catch (error) {
    status(`接收失败：${error}`, true)
  }
}
