package cn.autoplay.replacement;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

public final class UsbConnection implements UsbTransport {
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint inputEndpoint;
    private final UsbEndpoint outputEndpoint;

    private UsbConnection(
            UsbDeviceConnection connection,
            UsbInterface usbInterface,
            UsbEndpoint inputEndpoint,
            UsbEndpoint outputEndpoint) {
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.inputEndpoint = inputEndpoint;
        this.outputEndpoint = outputEndpoint;
    }

    public static UsbConnection open(UsbManager manager, UsbDevice device) throws IOException {
        UsbInterface selectedInterface = null;
        UsbEndpoint inputEndpoint = null;
        UsbEndpoint outputEndpoint = null;

        for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface candidate = device.getInterface(interfaceIndex);
            UsbEndpoint candidateInput = null;
            UsbEndpoint candidateOutput = null;

            for (int endpointIndex = 0; endpointIndex < candidate.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = candidate.getEndpoint(endpointIndex);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    candidateInput = endpoint;
                } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    candidateOutput = endpoint;
                }
            }

            if (candidateInput != null && candidateOutput != null) {
                selectedInterface = candidate;
                inputEndpoint = candidateInput;
                outputEndpoint = candidateOutput;
                break;
            }
        }

        if (selectedInterface == null) {
            throw new IOException("No interface has both bulk IN and bulk OUT endpoints.");
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("Android could not open the USB device.");
        }
        if (!connection.claimInterface(selectedInterface, true)) {
            connection.close();
            throw new IOException("Could not claim USB interface " + selectedInterface.getId() + ".");
        }

        return new UsbConnection(connection, selectedInterface, inputEndpoint, outputEndpoint);
    }

    public int getInterfaceId() {
        return usbInterface.getId();
    }

    public UsbEndpoint getInputEndpoint() {
        return inputEndpoint;
    }

    public UsbEndpoint getOutputEndpoint() {
        return outputEndpoint;
    }

    public int bulkRead(byte[] buffer, int offset, int length, int timeoutMillis) {
        return connection.bulkTransfer(inputEndpoint, buffer, offset, length, timeoutMillis);
    }

    public int bulkWrite(byte[] buffer, int offset, int length, int timeoutMillis) {
        return connection.bulkTransfer(outputEndpoint, buffer, offset, length, timeoutMillis);
    }

    @Override public String name() { return "Android USB"; }

    @Override
    public void close() {
        connection.releaseInterface(usbInterface);
        connection.close();
    }
}
