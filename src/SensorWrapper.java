import com.alibaba.fastjson.JSONObject;
import lib4wrapper.WrapperRemoteConnector;
import socket.CmdMessage;
import socket.CmdMsgType;
import struct.ResourceConfig;
import struct.enums.ResourceType;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SensorWrapper {
    // 定义监听 Python 端传感器数据的本地 UDP 端口
    private static final int LISTEN_UDP_PORT = 9093;

    // 使用 volatile 保证多线程下的内存可见性（用于缓存最新的传感器数据）
    private static volatile double currentX = 0.0;
    private static volatile double currentY = 0.0;
    private static volatile double currentYaw = 0.0;

    // 启动 UDP 监听线程，实时解析 Python 发来的传感器数据
    private static void startUdpListenerThread(DatagramSocket socket) {
        Thread listenerThread = new Thread(() -> {
            System.out.println("本地 UDP 监听已启动，端口: " + LISTEN_UDP_PORT + "，等待 Python 层推送数据...");
            byte[] receiveData = new byte[1024];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    socket.receive(receivePacket);

                    String jsonStr = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);

                    // 解析 Python 发来的 JSON 数据，例如：{"x": 1.25, "y": 3.42, "yaw": -90.5}
                    JSONObject pyData = JSONObject.parseObject(jsonStr);
                    if (pyData.containsKey("x")) currentX = pyData.getDoubleValue("x");
                    if (pyData.containsKey("y")) currentY = pyData.getDoubleValue("y");
                    if (pyData.containsKey("yaw")) currentYaw = pyData.getDoubleValue("yaw");

                } catch (Exception e) {
                    if (!socket.isClosed()) {
                        System.err.println("UDP 接收数据异常: " + e.getMessage());
                    }
                }
            }
        });

        // 设置为守护线程，主线程退出时自动结束
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public static void main(String[] args) {
        System.out.println("正在启动 Sensor Wrapper...");

        // 注册多域传感器，声明域为 x, y, yaw
        ResourceConfig config = new ResourceConfig("Sensor", ResourceType.SENSOR, List.of("x", "y", "yaw"));
        WrapperRemoteConnector connector = WrapperRemoteConnector.getInstance();
        DatagramSocket udpSocket = null;

        try {
            // 内部封装，向平台发送register报文，并等待返回注册结果响应报文
            if (connector.register("127.0.0.1", 9091, config)) {
                System.out.println("Sensor 已成功注册到 SEPAL 平台！");

                // 启动一个独立的后台线程，专门用于接收来自 Python 的 UDP 传感器数据流
                udpSocket = new DatagramSocket(LISTEN_UDP_PORT);
                startUdpListenerThread(udpSocket);

                while (true) {
                    // 阻塞式接收平台发送的报文
                    CmdMessage msg = connector.recv();

                    if (msg == null) continue;

                    switch (msg.cmd) {
                        case CmdMsgType.SENSORY_REQUEST:
                            // 构造多域传感器的返回 JSON
                            JSONObject value = new JSONObject();

                            // 直接从内存中读取最新的缓存数据
                            value.put("x", String.format("%.3f", currentX));
                            value.put("y", String.format("%.3f", currentY));
                            value.put("yaw", String.format("%.1f", currentYaw));

                            // 发送响应报文
                            CmdMessage response = new CmdMessage(CmdMsgType.SENSORY_BACK, value.toJSONString());
                            connector.send(response);
                            break;
                    }
                }
            }else {
                System.out.println("注册到 SEPAL 平台失败，请检查平台状态。");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }

            //内部封装，发送shutdown报文关闭连接
            connector.close();

            System.out.println("Sensor Wrapper 已安全关闭。");
        }
    }
}
