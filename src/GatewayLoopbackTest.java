import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class GatewayLoopbackTest {
    public static void main(String[] args) {
        int listenPort = 9093; // 模拟 SensorWrapper 监听端口
        int targetPort = 9092; // 模拟 Python 驱动监听端口

        try (DatagramSocket socket = new DatagramSocket(listenPort)) {
            System.out.println("Java 静态网关环回测试已启动，监听端口: " + listenPort);
            byte[] buffer = new byte[1024];

            while (true) {
                // 1. 接收来自 Python 的模拟传感器数据
                DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(receivePacket);
                String receivedJson = new String(receivePacket.getData(), 0, receivePacket.getLength());

                // 2. 模拟 ActorWrapper 直接原路打回
                InetAddress targetAddress = InetAddress.getByName("127.0.0.1");
                byte[] sendData = receivedJson.getBytes();
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, targetAddress, targetPort);
                socket.send(sendPacket);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}