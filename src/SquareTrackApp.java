import com.alibaba.fastjson.JSONObject;
import lib4app.AbstractApp;
import lib4app.AppRemoteConnector;
import struct.ActorInfo;
import struct.SensorData;
import struct.SensorInfo;
import struct.enums.CmdType;
import struct.enums.SensorMode;

import java.util.Map;

public class SquareTrackApp extends AbstractApp {
    // ==========================================
    // 核心参数配置区
    // ==========================================
    private static final double STRAIGHT_DISTANCE = 0.83; // 直道行驶目标距离 (米)
    private static final double FORWARD_SPEED = 0.4;      // 直行速度 (m/s)
    private static final double Kp_TURN = 2.0;            // 转向纠偏比例
    private static final double Kp_STRAIGHT = 1.5;        // 直行纠偏比例

    // 全局传感器缓存（使用 volatile 保证接收线程和主控线程之间的内存可见性）
    private volatile double currentX = 0.0;
    private volatile double currentY = 0.0;
    private volatile double currentYaw = 0.0;

    // 设备名称常量
    private static final String SENSOR_NAME = "Sensor";
    private static final String ACTOR_NAME = "Chassis";

    @Override
    public void configApp() {
        // 配置应用信息，在生成实例时被自动调用
        this.appName = "RoboMaster_SquareTrack";
        this.appDescription = "基于 SEPAL 平台的机器人回型跑道闭环控制应用";
    }

    @Override
    public void getMsg(String sensorName, SensorData sensorData) {
        // 以被动模式接收到传感器信息时将被自动调用 (20Hz)
        if (sensorName != null && sensorName.contains(SENSOR_NAME)) {
            try {
                // 解析 Wrapper 层传来的多域传感器数据
                // 平台解析底层传来的 JSON 后，可以通过 getData("域名") 获取
                currentX = Double.parseDouble(sensorData.getData("x").toString());
                currentY = Double.parseDouble(sensorData.getData("y").toString());
                currentYaw = Double.parseDouble(sensorData.getData("yaw").toString());
            } catch (Exception e) {
                System.err.println("解析传感器数据异常: " + e.getMessage());
            }
        }
    }

    // ==========================================
    // 算法与控制辅助函数
    // ==========================================

    // 计算角度差，处理 180/-180 跨界问题
    private double getAngleError(double target, double current) {
        double error = target - current;
        if (error > 180) {
            error -= 360;
        } else if (error < -180) {
            error += 360;
        }
        return error;
    }

    // 向底盘下发速度控制指令 (组装成 JSON 格式)
    private void sendDriveCmd(AppRemoteConnector connector, double x, double y, double z) {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "drive");
        cmd.put("x", x);
        cmd.put("y", y);
        cmd.put("z", z);
        connector.sendActorCmd(ACTOR_NAME, cmd.toJSONString());
    }

    // 向底盘下发停止指令
    private void sendStopCmd(AppRemoteConnector connector) {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "stop");
        connector.sendActorCmd(ACTOR_NAME, cmd.toJSONString());
    }

    // 精准转向到绝对角度
    private void turnToAbsoluteAngle(AppRemoteConnector connector, double targetYaw) throws InterruptedException {
        System.out.printf("正在精准转向至绝对角度: %.1f°\n", targetYaw);
        while (true) {
            double error = getAngleError(targetYaw, currentYaw);
            if (Math.abs(error) < 1.5) { // 误差小于 1.5 度视为对准
                sendStopCmd(connector);
                break;
            }
            double zSpeed = Kp_TURN * error;
            // 增加安全限幅，防止初始角度差过大导致起步过猛
            zSpeed = Math.max(Math.min(zSpeed, 100.0), -100.0);
            sendDriveCmd(connector, 0, 0, zSpeed);
            Thread.sleep(20); // 维持 50Hz 控制频率
        }
        Thread.sleep(300); // 停顿稳定车身
    }

    // 基于真实里程计 + IMU 航向锁定的直行
    private void driveStraightWithImu(AppRemoteConnector connector, double targetDist, double targetYaw) throws InterruptedException {
        System.out.printf("锁定 %.1f° 航向，目标直行 %.2f 米...\n", targetYaw, targetDist);

        // 记录起步那一瞬间的绝对坐标
        double startX = currentX;
        double startY = currentY;

        while (true) {
            // 使用勾股定理计算已走过的直线距离
            double traveledDist = Math.hypot(currentX - startX, currentY - startY);

            // 如果走过的距离达到了设定，立刻退出循环刹车
            if (traveledDist >= targetDist) {
                break;
            }

            // IMU 航向纠偏逻辑
            double error = getAngleError(targetYaw, currentYaw);
            double zCompensate = Kp_STRAIGHT * error;
            sendDriveCmd(connector, FORWARD_SPEED, 0, zCompensate);
            Thread.sleep(20);
        }
        sendStopCmd(connector);
        Thread.sleep(300);
    }

    public static void main(String[] args) {
        SquareTrackApp app = new SquareTrackApp();
        AppRemoteConnector connector = AppRemoteConnector.getInstance();

        try {
            System.out.println("正在连接 SEPAL 平台...");

            // 指定平台ip及端口port，连接远程平台
            if (!connector.connectPlatform("127.0.0.1", 9090)) {
                System.out.println("连接平台失败，请检查 SEPAL 核心服务是否启动。");
                return;
            }
            connector.registerApp(app);

            // 1. 注册 Sensor (被动模式，期望频率 20Hz)
            Map<String, SensorInfo> supportedSensors = connector.getSupportedSensors();
            if (supportedSensors.containsKey(SENSOR_NAME) && "ON".equals(supportedSensors.get(SENSOR_NAME).getStatus())) {
                connector.registerSensor(SENSOR_NAME, SensorMode.PASSIVE, 20);
                connector.getMsgThread(CmdType.START); // 启动数据接收线程
                System.out.println("已成功订阅 Sensor 数据流。");
            } else {
                System.out.println("未找到 Sensor 或其状态不为 ON，请先启动 SensorWrapper。");
                return;
            }

            // 2. 注册 Actor
            Map<String, ActorInfo> supportedActors = connector.getSupportedActors();
            if (supportedActors.containsKey(ACTOR_NAME)) {
                connector.registerActor(ACTOR_NAME);
                System.out.println("已成功接管 Chassis 控制权。");
            } else {
                System.out.println("未找到 Chassis 或其状态不为 ON，请先启动 ActorWrapper。");
                return;
            }

            // 等待 1 秒，让初始传感器数据流进来
            Thread.sleep(1000);

            // ==========================================
            // 跑圈核心逻辑执行区
            // ==========================================
            double baseYaw = app.currentYaw;

            // 规划 4 个目标角度 (回型顺时针，每次 -90 度)
            double[] squareAngles = {
                    baseYaw,
                    baseYaw - 90,
                    baseYaw - 180,
                    baseYaw - 270
            };

            // 将角度标准化到 -180 ~ 180 范围内
            for (int i = 0; i < squareAngles.length; i++) {
                if (squareAngles[i] > 180) squareAngles[i] -= 360;
                else if (squareAngles[i] < -180) squareAngles[i] += 360;
            }

            for (int lap = 0; lap < 5; lap++) {
                System.out.printf("\n====== 开始第 %d 圈 ======\n", lap + 1);
                for (int side = 0; side < squareAngles.length; side++) {
                    System.out.printf("--- 正在跑第 %d 条边 ---\n", side + 1);
                    double targetAngle = squareAngles[side];

                    // 1. 转身对齐
                    app.turnToAbsoluteAngle(connector, targetAngle);

                    // 2. 直行冲刺
                    app.driveStraightWithImu(connector, STRAIGHT_DISTANCE, targetAngle);
                }
            }

            System.out.println("\n跑圈任务圆满完成！");

        } catch (InterruptedException e) {
            System.out.println("任务被中断。");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("正在清理 SEPAL 平台资源...");
            app.sendStopCmd(connector); // 确保小车停下
            connector.cancelAllActors();
            connector.cancelAllSensors();
            connector.getMsgThread(CmdType.STOP);
            connector.unregisterApp(app);
            connector.disConnectPlatform();
            System.out.println("应用已安全退出。");
        }
    }
}
