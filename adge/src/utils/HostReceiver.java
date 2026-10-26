package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.ServerSocket;

public class HostReceiver {
    private static final Logger logger = LoggerFactory.getLogger(HostReceiver.class);

    private ServerSocket serverSocket;

    public HostReceiver(int port) {
        try {
            // loopback only: the Frida and uiautomator2 helpers run on the same host
            serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
            logger.debug("ServerSocket created ,port is " + port + ", waiting for connection");
        } catch (Exception e) {
            throw new IllegalStateException("cannot listen on port " + port, e);
        }
    }

    public ServerSocket getServerSocket() {
        return serverSocket;
    }
}
