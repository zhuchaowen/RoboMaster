import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import lib4wrapper.WrapperRemoteConnector;
import socket.CmdMessage;
import socket.CmdMsgType;
import struct.ResourceConfig;
import struct.enums.ResourceType;

public class ActorWrapper {
    // 定义 Python 层监听的本地 UDP 端口
    private static final String PYTHON_HOST = "127.0.0.1";
    private static final int PYTHON_UDP_PORT = 9092;

    public static void main(String[] args) {
        System.out.println("正在启动 Chassis Actor Wrapper...");

        ResourceConfig config = new ResourceConfig("Chassis", ResourceType.ACTOR, null);
        WrapperRemoteConnector connector = WrapperRemoteConnector.getInstance();
        DatagramSocket udpSocket = null;

        try {
            // 内部封装，向平台发送register报文，并等待返回注册结果响应报文
            if (connector.register("127.0.0.1", 9091, config)) { 
                System.out.println("已成功注册到 SEPAL 平台！");

                // 与python层通信：初始化本地 UDP Socket
                udpSocket = new DatagramSocket();
                InetAddress pythonAddress = InetAddress.getByName(PYTHON_HOST);
                System.out.println("本地 UDP 通信通道已建立，目标端口: " + PYTHON_UDP_PORT);

                while (true) {
                    // 阻塞式接收平台发送的报文
                    CmdMessage msg = connector.recv(); 

                    if (msg == null) continue;

                    switch (msg.cmd) {
                        // 根据具体指令执行相应操作
                        case CmdMsgType.ACTION_REQUEST:
                            String cmd = msg.message;
                            System.out.println("收到 SEPAL 平台指令：" + cmd);

                            // 根据msg执行操作：将指令通过 UDP 转发给本地的 Python 脚本
                            try {
                                byte[] sendData = cmd.getBytes(StandardCharsets.UTF_8);
                                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, pythonAddress, PYTHON_UDP_PORT);
                                udpSocket.send(sendPacket);
                                System.out.println(" -> 已将指令转发至 Python 层");
                            } catch (Exception e) {
                                System.err.println("转发指令至 Python 层失败: " + e.getMessage());
                            }
                            
                            // 发送响应报文，默认只要成功转发给 Python 就认为执行成功
                            CmdMessage response = new CmdMessage(CmdMsgType.ACTION_BACK, "true");
                            connector.send(response);
                            break;
                    }
                }
            } else {
                System.out.println("注册到 SEPAL 平台失败，请检查平台状态。");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }

            // 内部封装，发送shutdown报文关闭连接
            connector.close(); 

            System.out.println("Actor Wrapper 已安全关闭。");
        }
    }
}
