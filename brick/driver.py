import socket
import json
import time
import threading
from robomaster import robot

# ==========================================
# 网络通信配置区
# ==========================================
WRAPPER_IP = "127.0.0.1"
SENSOR_PORT = 9093  # Java SensorWrapper 监听的端口
ACTOR_PORT = 9092   # Python 监听的端口，接收 ActorWrapper 的指令

# 初始化 UDP Socket
udp_client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp_server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp_server.bind((WRAPPER_IP, ACTOR_PORT))

# 全局状态缓存
robot_state = {"x": 0.0, "y": 0.0, "yaw": 0.0}

# ==========================================
# 传感器感知逻辑 (向上输送数据)
# ==========================================
def send_sensor_data():
    """将最新状态打包为 JSON 发送给 Java SensorWrapper"""
    try:
        json_str = json.dumps(robot_state)
        udp_client.sendto(json_str.encode('utf-8'), (WRAPPER_IP, SENSOR_PORT))
    except Exception as e:
        print(f"发送传感器数据异常: {e}")

def position_callback(sub_info):
    """底盘里程计回调 (20Hz)"""
    robot_state["x"] = sub_info[0]
    robot_state["y"] = sub_info[1]
    send_sensor_data()

def attitude_callback(sub_info):
    """IMU 姿态回调 (20Hz)"""
    robot_state["yaw"] = sub_info[0]
    send_sensor_data()

# ==========================================
# 控制器执行逻辑 (向下接收指令)
# ==========================================
def cmd_listener_thread(ep_chassis):
    """守护线程：监听来自 Java ActorWrapper 的控制指令"""
    print(f"正在监听控制指令 (UDP 端口: {ACTOR_PORT})...")
    while True:
        try:
            data, addr = udp_server.recvfrom(1024)
            msg = data.decode('utf-8')

            # 约定 SEPAL 平台下发的控制指令为 JSON 格式，例如：
            # {"cmd": "drive", "x": 0.4, "y": 0.0, "z": 20.0}
            # {"cmd": "stop"}
            cmd_data = json.loads(msg)

            if cmd_data.get("cmd") == "drive":
                speed_x = cmd_data.get("x", 0.0)
                speed_y = cmd_data.get("y", 0.0)
                speed_z = cmd_data.get("z", 0.0)
                ep_chassis.drive_speed(x=speed_x, y=speed_y, z=speed_z)

            elif cmd_data.get("cmd") == "stop":
                ep_chassis.drive_speed(x=0.0, y=0.0, z=0.0)

        except json.JSONDecodeError:
            print(f"指令解析失败 (非合法 JSON): {msg}")
        except Exception as e:
            print(f"指令执行异常: {e}")

if __name__ == '__main__':
    ep_robot = robot.Robot()
    print("正在连接物理小车...")
    ep_robot.initialize(conn_type="ap") 
    ep_chassis = ep_robot.chassis

    # 1. 启动 UDP 监听线程接收速度指令
    listener_thread = threading.Thread(target=cmd_listener_thread, args=(ep_chassis,))
    listener_thread.daemon = True # 设置为守护线程
    listener_thread.start()

    try:
        # 2. 开启 Position (里程计) 和 IMU 的数据监听，频率 20Hz
        ep_chassis.sub_position(freq=20, callback=position_callback)
        ep_chassis.sub_attitude(freq=20, callback=attitude_callback)
        print("传感器数据订阅成功，正在持续向 SEPAL Wrapper 输送数据...")
        print("物理层已就绪，等待 SEPAL 平台应用层接管大脑！")

        # 3. 保持主线程存活，现在这里不需要写 while True 死循环跑圈了
        while True:
            time.sleep(1)

    except KeyboardInterrupt:
        print("\n接收到手动中断指令，准备清理现场...")
    finally:
        # 安全断开机制
        ep_chassis.drive_speed(x=0, y=0, z=0)
        ep_chassis.unsub_attitude()
        ep_chassis.unsub_position()
        ep_robot.close()
        udp_server.close()
        udp_client.close()
        print("物理小车已安全断开，Python 驱动层退出。")