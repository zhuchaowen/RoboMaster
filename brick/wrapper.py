import socket
import json
import time
import threading
from robomaster import robot

# ==========================================
# 网络配置区 (与 SEPAL 平台完美对齐)
# ==========================================
ODOMETER_ADDR = ("127.0.0.1", 5005) # 上行：汇报底盘里程计数据
DISTANCE_ADDR = ("127.0.0.1", 5006) # 上行：汇报红外测距数据
ACTOR_PORT = 5007                   # 下行：监听 SEPAL 平台下发指令

if __name__ == '__main__':
    print("================================")
    print("🚀 启动 RoboMaster EP 边缘网关")
    print("================================")
    
    ep_robot = robot.Robot()

    # AP 直连模式
    print("🤖 正在连接 EP 机甲 (AP 直连模式)...")
    ep_robot.initialize(conn_type='ap')
    print("✅ 连接成功！")

    version = ep_robot.get_version()
    print(f"Robot version: {version}")
    SN = ep_robot.get_sn()
    print(f"Robot SN: {SN}")

    # ==========================================
    # 获取所有需要的硬件模块句柄
    # ==========================================
    ep_chassis = ep_robot.chassis   # Actor 1 / Sensor 1: 底盘
    ep_arm = ep_robot.robotic_arm   # Actor 2: 机械臂
    ep_gripper = ep_robot.gripper   # Actor 3: 机械爪
    ep_sensor = ep_robot.sensor     # Sensor 2: 传感器

    # 创建用于发送传感器数据的 UDP Socket
    sensor_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    # ==========================================
    # 传感器轨道 - 向上汇报数据
    # ==========================================
    print("👁️ 初始化双轨感知链路...")
    
    # 里程计回调：不断汇报已走过的距离 (解决全局定位)
    def on_position_update(pos_info):
        x, y, z = pos_info
        data = {"sensor": "odometer", "current_x": round(x, 3)}
        try:
            sensor_sock.sendto(json.dumps(data).encode('utf-8'), ODOMETER_ADDR)
        except Exception:
            pass

    # 红外测距回调：不断汇报前方障碍物距离 (解决最后精准对准)
    def on_distance_update(dist_info):
        distance_mm = dist_info[0] # 获取红外探头测得的距离
        data = {"sensor": "tof", "distance_mm": distance_mm}
        try:
            sensor_sock.sendto(json.dumps(data).encode('utf-8'), DISTANCE_ADDR)
        except Exception:
            pass

    # 开启订阅 (freq=10 表示一秒钟向 Java 平台甩 10 次数据)
    ep_chassis.sub_position(freq=10, callback=on_position_update)
    ep_sensor.sub_distance(freq=10, callback=on_distance_update)
    print("✅ 里程计与红外传感器已开启，数据持续推送中...")

    # ==========================================
    # 控制器轨道 - 接收并执行动作
    # ==========================================
    print("👂 初始化指令下行链路...")
    
    def listen_java_commands():
        actor_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        actor_sock.bind(("127.0.0.1", ACTOR_PORT))
        print(f"✅ 动作监听线程已就绪，正在监听 UDP {ACTOR_PORT}...")

        while True:
            try:
                # 阻塞等待 SEPAL 的 ActorWrapper 发来的指令
                data, addr = actor_sock.recvfrom(1024)
                command = json.loads(data.decode('utf-8'))
                cmd_type = command.get("cmd")

                # 阶段 1：巡航
                if cmd_type == "drive":
                    x_speed = command.get("x_speed", 0.0)
                    ep_chassis.drive_speed(x=x_speed, y=0, z=0)
                
                # 阶段 2：制动
                elif cmd_type == "stop":
                    ep_chassis.drive_speed(x=0, y=0, z=0)
                    print("🛑 收到平台刹车指令，底盘停转。")
                
                # 阶段 3：抓取作业(SEPAL平台判断距离达标并停稳后，才会下发此指令)
                elif cmd_type == "grab":
                    print("⚡ 距离达标，开始执行抓取任务！")
                    ep_gripper.open(power=2)
                    time.sleep(0.5)
                    
                    # .wait_for_completed() 保证上一个动作做完再做下一个
                    ep_arm.move(x=100, y=-50).wait_for_completed() # 机械臂前伸下压
                    ep_gripper.close(power=2)                      # 夹爪闭合
                    time.sleep(1)                                  # 停顿1秒等待夹紧
                    ep_arm.move(x=-100, y=50).wait_for_completed() # 机械臂抬起收回
                    
                    print("✔️ 抓取作业完美结束！")

            except Exception as e:
                print(f"⚠️ 指令解析或执行异常: {e}")

    # 启动后台监听子线程
    actor_thread = threading.Thread(target=listen_java_commands, daemon=True)
    actor_thread.start()

    # ==========================================
    # 守护主进程与资源释放
    # ==========================================
    print("🛡️ 边缘网关全面运行中！")
    print("================================")
    
    try:
        # 主线程死循环睡觉，维持程序不死
        while True:
            time.sleep(1)
            
    except KeyboardInterrupt:
        print("\n🛑 收到退出信号，正在执行清理工作...")
    finally:
        # 优雅停机，释放硬件连接，防止下次启动报错
        ep_chassis.unsub_position()
        ep_sensor.unsub_distance()
        ep_robot.close()
        sensor_sock.close()
        print("👋 节点已安全关闭，再见！")