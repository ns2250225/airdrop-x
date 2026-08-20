package io.airdropx.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

private const val companyId = 0xfffe
private val marker = "ADX1".toByteArray()

class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var devices: TextView
    private lateinit var receiveDirectory: TextView
    private lateinit var status: TextView
    private lateinit var wifi: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private val found = linkedMapOf<String, String>()
    private var receiving = false
    private var receiveTree: Uri? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "AirDrop-X Windows"
            found[result.device.address] = "$name  ${result.rssi} dBm"
            runOnUiThread { devices.text = found.values.joinToString("\n") }
        }

        override fun onScanFailed(code: Int) = show("BLE 扫描失败：$code")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(code: Int) = show("BLE 广播失败：$code")
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        wifi = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifi.initialize(this, mainLooper, null)
        receiveTree = getPreferences(MODE_PRIVATE).getString("receive_tree", null)?.let(Uri::parse)
        setContentView(content())
        requestRadioPermissions()
    }

    private fun content(): LinearLayout {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(242, 245, 250))
            addView(TextView(this@MainActivity).apply { text = "AirDrop-X"; textSize = 28f; setTextColor(Color.rgb(23, 32, 51)) })
            addView(TextView(this@MainActivity).apply { text = "BLE 发现 · Wi‑Fi Direct · TCP 文件传输"; setPadding(0, 4, 0, pad) })
            devices = TextView(this@MainActivity).apply { text = "尚未扫描附近设备"; setPadding(0, pad / 2, 0, pad / 2) }
            addView(devices, match())
            addView(Button(this@MainActivity).apply { text = "扫描附近 Windows 设备"; setOnClickListener { scanBle() } }, match())
            receiveDirectory = TextView(this@MainActivity).apply { text = receiveTree?.let { "接收目录：${it.lastPathSegment}" } ?: "接收目录：应用专属目录" }
            addView(receiveDirectory, match())
            addView(Button(this@MainActivity).apply { text = "选择接收目录"; setOnClickListener { chooseReceiveDirectory() } }, match())
            addView(Button(this@MainActivity).apply { text = "开启 Wi‑Fi Direct 并接收"; setOnClickListener { createGroup() } }, match())
            address = EditText(this@MainActivity).apply { hint = "Windows 直连地址（发送时使用）"; setText("192.168.49.20:48765") }
            addView(address, match())
            addView(Button(this@MainActivity).apply { text = "选择文件并发送"; setOnClickListener { chooseFile() } }, match())
            status = TextView(this@MainActivity).apply { text = "正在初始化无线功能…"; gravity = 17; setPadding(0, pad, 0, 0) }
            addView(status, match())
        }
    }

    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun requestRadioPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startAdvertising()
        else requestPermissions(permissions, 2)
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == 2 && results.all { it == PackageManager.PERMISSION_GRANTED }) startAdvertising()
        else show("需要蓝牙和附近设备权限")
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val advertiser = adapter.bluetoothLeAdvertiser ?: return show("此设备不支持 BLE 广播")
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(false).build()
        val data = AdvertiseData.Builder().addManufacturerData(companyId, marker).build()
        val response = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, response, advertiseCallback)
        show("BLE 广播已开启")
    }

    @SuppressLint("MissingPermission")
    private fun scanBle() {
        found.clear()
        devices.text = "正在扫描…"
        val scanner = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner
            ?: return show("蓝牙未开启")
        val filter = ScanFilter.Builder().setManufacturerData(companyId, marker).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        devices.postDelayed({ scanner.stopScan(scanCallback); if (found.isEmpty()) devices.text = "未发现 AirDrop-X Windows 设备" }, 4_000)
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        show("正在创建 Wi‑Fi Direct 网络…")
        wifi.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                show("直连网络已就绪，等待 Windows 连接…")
                if (!receiving) receive()
            }
            override fun onFailure(reason: Int) = show(wifiDirectFailure(reason))
        })
    }

    private fun wifiDirectFailure(reason: Int) = when (reason) {
        WifiP2pManager.ERROR ->
            "Wi‑Fi Direct 建组失败：系统内部错误。请确认 Wi‑Fi 已开启、附近设备权限已授予，然后关闭并重新打开 Wi‑Fi 后重试（错误码 0）"
        WifiP2pManager.P2P_UNSUPPORTED ->
            "Wi‑Fi Direct 建组失败：此手机或当前系统不支持 Wi‑Fi Direct（错误码 1）"
        WifiP2pManager.BUSY ->
            "Wi‑Fi Direct 建组失败：系统正忙，可能已有直连网络或正在连接其他设备。请稍等后重试，必要时断开现有 Wi‑Fi Direct 连接（错误码 2）"
        else ->
            "Wi‑Fi Direct 建组失败：系统返回了未知错误，请重启 Wi‑Fi 后重试（错误码 $reason）"
    }

    private fun chooseFile() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
    }, 1)

    private fun chooseReceiveDirectory() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }, 3)

    @Deprecated("V0.1 keeps the dependency-free platform API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) return
        val intent = data ?: return
        if (request == 1) intent.data?.let(::send)
        if (request == 3) intent.data?.let { tree ->
            contentResolver.takePersistableUriPermission(tree, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            receiveTree = tree
            getPreferences(MODE_PRIVATE).edit().putString("receive_tree", tree.toString()).apply()
            receiveDirectory.text = "接收目录：${tree.lastPathSegment}"
        }
    }

    private fun send(uri: Uri) = work("正在发送…") {
        val (host, port) = endpoint()
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null).use { cursor ->
            val row = cursor ?: error("无法读取文件信息")
            check(row.moveToFirst()) { "无法读取文件信息" }
            val name = row.getString(0).toByteArray()
            val size = row.getLong(1)
            val hash = contentResolver.openInputStream(uri)!!.use(::digest)
            DataOutputStream(Socket(host, port).getOutputStream().buffered()).use { output ->
                output.write("ADX1".toByteArray()); output.writeShort(name.size); output.write(name)
                output.writeLong(size); output.write(hash)
                contentResolver.openInputStream(uri)!!.use { it.copyTo(output) }
            }
        }
        "发送完成"
    }

    private fun receive() {
        receiving = true
        show("等待 Windows 连接…")
        Thread {
            val directory = getExternalFilesDir("received") ?: filesDir
            directory.mkdirs()
            ServerSocket(48765).use { server ->
                while (receiving) server.accept().use { socket ->
                    val peer = "${socket.inetAddress.hostAddress}:48765"
                    DataInputStream(socket.getInputStream().buffered()).use { input ->
                        when (String(input.readExact(4))) {
                            "ADXP" -> runOnUiThread { address.setText(peer); status.text = "已连接：$peer" }
                            "ADX1" -> show(runCatching { receiveFile(input, directory) }.fold({ "已接收：$it" }, { "接收失败：${it.message}" }))
                            else -> show("收到未知协议")
                        }
                    }
                }
            }
        }.start()
    }

    private fun receiveFile(input: DataInputStream, directory: File): String {
        val name = File(String(input.readExact(input.readUnsignedShort()))).name
        val size = input.readLong()
        val expected = input.readExact(32)
        receiveTree?.let { return receiveToTree(input, it, name, size, expected) }
        val part = File(directory, "$name.airdrop-x.part")
        FileOutputStream(part).use { output -> copy(input, output, size) }
        check(part.length() == size && FileInputStream(part).use(::digest).contentEquals(expected)) { "文件不完整或校验失败" }
        val target = File(directory, name)
        check(part.renameTo(target)) { "无法保存文件" }
        return target.absolutePath
    }

    private fun receiveToTree(input: DataInputStream, tree: Uri, name: String, size: Long, expected: ByteArray): String {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val part = DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", "$name.airdrop-x.part")
            ?: error("无法在所选目录创建文件")
        try {
            val copied = contentResolver.openOutputStream(part, "w")!!.use { copy(input, it, size) }
            check(copied == size && contentResolver.openInputStream(part)!!.use(::digest).contentEquals(expected)) { "文件不完整或校验失败" }
            val renamed = DocumentsContract.renameDocument(contentResolver, part, name) ?: error("无法重命名文件")
            return renamed.lastPathSegment ?: name
        } catch (error: Throwable) {
            DocumentsContract.deleteDocument(contentResolver, part)
            throw error
        }
    }

    private fun endpoint(): Pair<String, Int> {
        val parts = address.text.toString().trim().split(':')
        require(parts.size == 2) { "地址格式应为 IP:端口" }
        return parts[0] to parts[1].toInt()
    }

    private fun work(message: String, action: () -> String) {
        show(message)
        Thread { show(runCatching(action).fold({ it }, { "失败：${it.message}" })) }.start()
    }

    private fun show(message: String) = runOnUiThread { if (::status.isInitialized) status.text = message }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
        adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        wifi.removeGroup(channel, null)
        super.onDestroy()
    }
}

private fun digest(input: InputStream): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return digest.digest()
        digest.update(buffer, 0, read)
    }
}

private fun DataInputStream.readExact(size: Int) = ByteArray(size).also(::readFully)

private fun copy(input: InputStream, output: OutputStream, size: Long): Long {
    var remaining = size
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(read > 0) { "连接提前关闭" }
        output.write(buffer, 0, read)
        remaining -= read
    }
    return size
}
