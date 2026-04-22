package main.java.wrapper.sensor;

import com.alibaba.fastjson.JSONObject;
import lib4wrapper.WrapperRemoteConnector;
import socket.CmdMessage;
import socket.CmdMsgType;
import struct.ResourceConfig;
import struct.enums.ResourceType;
import main.java.wrapper.Resource;

import java.util.List;

public class SensorWrapper {
    public static void main(String[] args) {
        ResourceConfig config = new ResourceConfig("sensor", ResourceType.SENSOR, List.of("chassis", "vision"));
        WrapperRemoteConnector connector = WrapperRemoteConnector.getInstance();
        if (connector.register("127.0.0.1", 9091, config)) { //内部封装，向平台发送register报文，并等待返回注册结果响应报文
            while (true) {
                CmdMessage msg = connector.recv(); //阻塞式接收平台发送的报文
                if (msg == null) continue;
                switch (msg.cmd) {
                    // 根据具体指令执行相应操作
                    case CmdMsgType.SENSORY_REQUEST:
                        JSONObject value = new JSONObject();

                        for (String key : Resource.sensorData.keySet()) {
                            if (Resource.sensorData.get(key).isEmpty()) {
                                value.put(key, "");
                            } else{
                                String v = Resource.sensorData.get(key).removeFirst();
                                if(v == null)   value.put(key, "");
                                value.put(key, v);
                            }
                        }

                        // 发送响应报文
                        CmdMessage response = new CmdMessage(CmdMsgType.SENSORY_BACK, value.toJSONString());
                        connector.send(response);
                        break;
                }
            }
        }
    }
}
