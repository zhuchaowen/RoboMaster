import time
import math
from robomaster import robot

# ==========================================
# 核心参数配置区
# =========================================
STRAIGHT_DISTANCE = 0.83 # 直道行驶目标距离 (米)
FORWARD_SPEED = 0.4      # 直行速度 (m/s)

Kp_TURN = 2.0            # 转向纠偏比例 
Kp_STRAIGHT = 1.5        # 直行纠偏比例 

# 全局传感器数据
current_x = 0.0         
current_y = 0.0     
current_yaw = 0.0   

def position_callback(sub_info):
    """实时读取底盘里程计坐标"""
    global current_x, current_y
    # sub_info 包含 (x, y, z)，只需要 x 和 y
    current_x = sub_info[0]
    current_y = sub_info[1]

def attitude_callback(sub_info):
    """实时读取 IMU 偏航角"""
    global current_yaw
    current_yaw = sub_info[0]

def get_angle_error(target, current):
    """计算角度差，处理 180/-180 跨界问题"""
    error = target - current
    if error > 180:
        error -= 360
    elif error < -180:
        error += 360
    return error

def turn_to_absolute_angle(ep_chassis, target_yaw):
    """精准转向到绝对角度"""
    print(f"正在精准转向至绝对角度: {target_yaw:.1f}°")
    while True:
        error = get_angle_error(target_yaw, current_yaw)
        if abs(error) < 1.5:  # 误差小于 1.5 度视为对准
            ep_chassis.drive_speed(x=0, y=0, z=0)
            break
        z_speed = Kp_TURN * error
        ep_chassis.drive_speed(x=0, y=0, z=z_speed)
        time.sleep(0.02)
    time.sleep(0.3) # 停顿稳定车身

def drive_straight_with_imu(ep_chassis, target_dist, target_yaw):
    """基于真实里程计 + IMU 航向锁定的直行"""
    print(f"锁定 {target_yaw:.1f}° 航向，目标直行 {target_dist} 米...")
    
    # 记录起步那一瞬间的绝对坐标
    start_x = current_x
    start_y = current_y
    
    while True:
        # 使用勾股定理计算已走过的直线距离
        traveled_dist = math.hypot(current_x - start_x, current_y - start_y)
        
        # 如果走过的距离达到了设定，立刻退出循环刹车
        if traveled_dist >= target_dist:
            break
            
        # IMU 航向纠偏逻辑
        error = get_angle_error(target_yaw, current_yaw)
        z_compensate = Kp_STRAIGHT * error
        ep_chassis.drive_speed(x=FORWARD_SPEED, y=0, z=z_compensate)
        time.sleep(0.02)
        
    ep_chassis.drive_speed(x=0, y=0, z=0)
    time.sleep(0.3)

if __name__ == '__main__':
    ep_robot = robot.Robot()

    print("正在连接小车...")
    ep_robot.initialize(conn_type="ap")

    ep_chassis = ep_robot.chassis

    try:
        # 开启 Position (里程计) 和 IMU 的数据监听
        ep_chassis.sub_position(freq=20, callback=position_callback)
        ep_chassis.sub_attitude(freq=20, callback=attitude_callback)
        time.sleep(1) 
            

        # 基准角度
        base_yaw = current_yaw

        # ==========================================
        # 跑圈逻辑
        # ==========================================
        # 规划 4 个目标角度 (回型顺时针，每次 -90 度)
        square_angles = [
            base_yaw, 
            base_yaw - 90, 
            base_yaw - 180, 
            base_yaw - 270
        ]
        # 将角度标准化到 -180 ~ 180 范围内
        square_angles = [a - 360 if a > 180 else (a + 360 if a < -180 else a) for a in square_angles]

        start_time = time.time()
        print("开始计时：跑圈任务启动")

        for lap in range(5):
            print(f"\n====== 开始第 {lap+1} 圈 ======")
            for side, target_angle in enumerate(square_angles):
                print(f"--- 正在跑第 {side+1} 条边 ---")
                # 1. 转身对齐
                turn_to_absolute_angle(ep_chassis, target_angle)
                # 2. 直行冲刺
                drive_straight_with_imu(ep_chassis, target_dist=STRAIGHT_DISTANCE, target_yaw=target_angle)

        end_time = time.time()
        total_duration = end_time - start_time
        print(f"任务完成！跑完 5 圈总耗时: {total_duration:.2f} 秒")

    except KeyboardInterrupt:
        print("\n接收到手动中断指令")
    finally:
        # 安全断开
        ep_chassis.drive_speed(x=0, y=0, z=0)
        ep_chassis.unsub_attitude()
        ep_chassis.unsub_position()
        ep_robot.close()
        print("机器人已断开。")
        