package com.example.appadc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var btnConnectBluetooth: ImageButton
    private lateinit var btnLoadData: Button
    private lateinit var btnStopSending: Button
    private lateinit var btnSaveData: ImageButton
    private var isReceivingData = false
    private lateinit var bluetoothSocket: BluetoothSocket
    private lateinit var dataReceiverThread: Thread
    private val REQUEST_PERMISSION_CODE = 101
    private val REQUEST_EXPORT_CODE = 102
    private lateinit var exportDataLauncher: ActivityResultLauncher<Intent>



    private val BLUETOOTH_PERMISSION_CODE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chart = findViewById(R.id.chart)
        btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth)
        btnLoadData = findViewById(R.id.btnLoadData)
        btnStopSending = findViewById(R.id.btnStopSending)
        btnSaveData = findViewById(R.id.btnSaveData)
        btnSaveData.isEnabled = false
        exportDataLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {

                Toast.makeText(this, "Datos exportados exitosamente", Toast.LENGTH_SHORT).show()
            } else {
               
                Toast.makeText(this, "Error al exportar los datos", Toast.LENGTH_SHORT).show()
            }
        }




        btnConnectBluetooth.setOnClickListener {
            requestBluetoothPermission()
        }

        btnLoadData.setOnClickListener {
            startReceivingData()
        }

        btnStopSending.setOnClickListener {
            stopReceivingData()
        }

        btnSaveData.setOnClickListener {
            // Agrega la lógica para guardar los datos recibidos en un archivo
            saveData()
        }
    }

    private fun requestBluetoothPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH),
                BLUETOOTH_PERMISSION_CODE
            )
        } else {
            connectToAnyBluetoothDevice()
        }
    }

    private fun connectToAnyBluetoothDevice() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val pairedDevices: Set<BluetoothDevice>? =
                BluetoothAdapter.getDefaultAdapter().bondedDevices
            pairedDevices?.let {
                val deviceList = ArrayList(it)
                val deviceNames = deviceList.map { device -> device.name }.toTypedArray()

                val builder = AlertDialog.Builder(this)
                builder.setTitle("Select a Bluetooth Device")
                builder.setItems(deviceNames) { dialog, which ->
                    val selectedDevice = deviceList[which]
                    connectToDevice(selectedDevice)
                }
                builder.show()
            }
        } else {
            // Handle the case where Bluetooth permission is not granted
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH),
                BLUETOOTH_PERMISSION_CODE
            )
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothSocket.connect()
                btnLoadData.isEnabled = true
                btnConnectBluetooth.setImageResource(R.drawable.bluetooth__c)
                btnLoadData.backgroundTintList = getColorStateList(R.color.green)
                // getResources().getColor(R.color.green)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else {
            // Handle the case where Bluetooth permission is not granted
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH),
                BLUETOOTH_PERMISSION_CODE
            )
        }
    }


    private fun startReceivingData() {
        val inputStream: InputStream = bluetoothSocket.inputStream
        val buffer = ByteArray(1024)
        var bytesRead: Int

        val entries = ArrayList<Entry>()
        val dataSet = LineDataSet(entries, "Data ADC")
        val lineData = LineData(dataSet)
        chart.data = lineData

        isReceivingData = true
        dataReceiverThread = Thread {
            while (isReceivingData) {
                try {
                    bytesRead = inputStream.read(buffer)
                    val data = String(buffer, 0, bytesRead).trim()
                    val value = data.toFloatOrNull()
                    if (value != null) {
                        runOnUiThread {
                            val entry = Entry(dataSet.entryCount.toFloat(), value)
                            dataSet.addEntry(entry)
                            lineData.notifyDataChanged()
                            chart.notifyDataSetChanged()
                            chart.invalidate()
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        dataReceiverThread.start()
        btnStopSending.isEnabled = true
        btnStopSending.backgroundTintList = getColorStateList(R.color.red)
        btnSaveData.isEnabled = true
    }

    private fun stopReceivingData() {
        isReceivingData = false
        try {
            dataReceiverThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        btnStopSending.isEnabled = false
    }

    private fun saveData() {
        val dataToExport = StringBuilder()

        val dataSet = chart.data.dataSets.firstOrNull() as? LineDataSet
        dataSet?.let {
            val entries = dataSet.values

            for (entry in entries) {
                dataToExport.append("${entry.x},${entry.y}\n")
            }

            val fileName = "data.txt"
            val mimeType = "text/plain" // Tipo MIME para archivos de texto plano

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_PERMISSION_CODE
                )
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)

                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(dataToExport.toString().toByteArray())
                        outputStream.close()
                        Toast.makeText(this, "Datos exportados exitosamente", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }




    override fun onDestroy() {
        super.onDestroy()
        isReceivingData = false
        try {
            bluetoothSocket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}


