package main.java.wrapper.actor;

import lib4wrapper.WrapperRemoteConnector;
import socket.CmdMessage;
import socket.CmdMsgType;
import struct.ResourceConfig;
import struct.enums.ResourceType;

public class GripperActorWrapper {
    public static void main(String[] args) {
        ResourceConfig config = new ResourceConfig("gripper", ResourceType.ACTOR, null);
        WrapperRemoteConnector connector = WrapperRemoteConnector.getInstance();
        if (connector.register("127.0.0.1", 9091, config)) { //内部封装，向平台发送register报文，并等待返回注册结果响应报文
            while (true) {
                CmdMessage msg = connector.recv(); //阻塞式接收平台发送的报文
                switch (msg.cmd) {
                    // 根据具体指令执行相应操作
                    case CmdMsgType.ACTION_REQUEST:
                        String cmd = msg.message;
                        //根据msg执行操作
                        System.out.println("Execute command: " + cmd);
                        // 发送响应报文
                        CmdMessage response = new CmdMessage(CmdMsgType.ACTION_BACK, "result");
                        connector.send(response);
                        break;
                }
            }
        }
    }
}
