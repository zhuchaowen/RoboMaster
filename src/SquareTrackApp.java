import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lib4app.AbstractApp;
import lib4app.AppRemoteConnector;
import lib4app.DBController;
import lib4app.InvCheck;
import struct.ActorInfo;
import struct.InvServiceConfig;
import struct.SensorData;
import struct.SensorInfo;
import struct.enums.*;
import struct.sync.SynchronousSensorData;

import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SquareTrackApp extends AbstractApp {
    public Map<String, SynchronousSensorData> invReport = new ConcurrentHashMap<>();

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

    private volatile long t0_py_send = 0;
    private volatile long t1_app_recv = 0;

    private volatile long currentLatency = 0;
    private PrintWriter csvWriter;

    // 设备与数据库常量
    private static final String SENSOR_NAME = "Sensor";
    private static final String ACTOR_NAME = "Chassis";
    private static final String TABLE_NAME = "robot_trajectory"; // 定义监控轨迹的表名

    // 数据库控制器实例
    private DBController dbController;

    @Override
    public void configApp() {
        // 配置应用信息，在生成实例时被自动调用
        this.appName = "RoboMaster_SquareTrack";
        this.appDescription = "基于 SEPAL 平台的机器人回型跑道闭环控制应用";
    }

    @Override
    public void getMsg(String sensorName, SensorData sensorData) {
        // 拦截不变式检测结果
        if (sensorData.getType() == SensorDataType.INV_REPORT) {
            // 如果数据类型是不变式服务结果，放入全局队列
            invReport.computeIfAbsent(sensorName, k -> new SynchronousSensorData()).put(sensorData);
            return; // 拦截完毕直接返回
        }

        // 以被动模式接收到传感器信息时将被自动调用 (20Hz)
        if (sensorName != null && sensorName.contains(SENSOR_NAME)) {
            // T1: 大脑收到数据的瞬间
            t1_app_recv = System.currentTimeMillis();

            try {
                // 解析 Wrapper 层传来的多域传感器数据
                // 平台解析底层传来的 JSON 后，可以通过 getData("域名") 获取
                currentX = Double.parseDouble(sensorData.getData("x").toString());
                currentY = Double.parseDouble(sensorData.getData("y").toString());
                currentYaw = Double.parseDouble(sensorData.getData("yaw").toString());

                // 获取 t0_py_send
                Object t0Obj = sensorData.getData("t0_py_send");
                if (t0Obj != null) {
                    t0_py_send = Long.parseLong(t0Obj.toString());
                }

                // 将实时状态与时延写入平台内置数据库
                if (dbController != null) {
                    Map<String, Object> row = new HashMap<>();
                    // 使用当前接收时间戳作为主键
                    row.put("timestamp", String.valueOf(t1_app_recv));
                    row.put("x", currentX);
                    row.put("y", currentY);
                    row.put("yaw", currentYaw);

                    // 计算上行及轮询总时延
                    currentLatency = (t0_py_send > 0) ? (t1_app_recv - t0_py_send) : 0;
                    row.put("uplink_latency", currentLatency);

                    // 异步插入单行数据
                    dbController.insertRow(TABLE_NAME, row);
                }

                // 将这一帧数据追加写入本地文件
                if (csvWriter != null) {
                    // 使用逗号分隔每一个数据，\n 换行
                    csvWriter.printf("%d,%.3f,%.3f,%.3f,%d\n",
                            t1_app_recv, currentX, currentY, currentYaw, currentLatency);
                    csvWriter.flush(); // 强制立刻刷入硬盘，防止程序崩溃丢失数据
                }
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
        InvCheck checker = InvCheck.getInstance();
        if (checker != null) {
            // 提交待下发的速度变量进行检测
            checker.check(x, z);

            try {
                // 阻塞等待 SEPAL 平台返回结果。
                SensorData data = invReport.computeIfAbsent("INV_REPORT140", k -> new SynchronousSensorData()).blockTake();
                CheckResult result = checker.getResult(data);

                // 如果平台判定违规
                if (result == CheckResult.INV_VIOLATED) {
                    System.err.printf("熔断触发！拦截到异常指令: speed_x=%.2f, speed_z=%.2f\n", x, z);
                    // 下发停车指令，切断原操作
                    sendStopCmd(connector);
                    return;
                }
            } catch (Exception e) {
                System.err.println("不变式检测出现异常: " + e.getMessage());
            }
        }

        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "drive");
        cmd.put("x", x);
        cmd.put("y", y);
        cmd.put("z", z);

        cmd.put("t0_py_send", t0_py_send);
        cmd.put("t1_app_recv", t1_app_recv);

        // T2: 大脑发出指令的瞬间
        cmd.put("t2_app_send", System.currentTimeMillis());

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

            // 新增核心代码：配置并启动不变式服务
            InvServiceConfig invConfig = new InvServiceConfig();
            invConfig.setGrpOn(false);
            invConfig.setInvGenThro(50); // 收集 50 帧数据后自动生成不变式约束

            invConfig.setChkFiles(List.of("src/SquareTrackApp.java"));

            connector.serviceStart(ServiceType.INV, invConfig);

            InvCheck checker = InvCheck.getInstance();

            // 声明我们要监控的底层速度变量
            double speed_x = 0.0, speed_z = 0.0;
            checker.monitor(speed_x, speed_z);
            System.out.println("SEPAL 不变式防御服务启动完毕！");

            // 创建本地 CSV 文件，准备导出给 Excel
            try {
                // 在项目根目录下自动创建一个 robot_data.csv 文件
                app.csvWriter = new PrintWriter(new FileWriter("robot_data.csv"));
                // 写入 Excel 的第一行（表头）
                app.csvWriter.println("Timestamp,X_Position,Y_Position,Yaw_Angle,Uplink_Latency_ms");
                app.csvWriter.flush();
                System.out.println("📊 数据记录仪启动成功！数据将实时保存至 robot_data.csv");
            } catch (IOException e) {
                System.out.println("创建 CSV 文件失败: " + e.getMessage());
            }

            // 注册自定义 Web 监控界面
            // 第一个参数是浏览器里敲的网址后缀，第二个参数是你本地编写的网页文件名
            boolean uiReady = connector.setUI("monitor.jsp", "dashboard.html");
            System.out.println("监控界面部署状态: " + uiReady + "，请访问: http://localhost:8080/monitor.jsp");

            // 获取数据库句柄并初始化监控表
            app.dbController = connector.getDBControllerInstance();
            if (app.dbController != null) {
                // 每次启动重置历史轨迹表，确保图表从零绘制
                app.dbController.deleteTable(TABLE_NAME);

                // 创建包含时间戳、位置、偏航角、上行时延的监控表，最大容量限制10000行防止缓存积压
                boolean isCreated = app.dbController.createTable(
                        TABLE_NAME,
                        "timestamp",
                        List.of("timestamp", "x", "y", "yaw", "uplink_latency"),
                        10000
                );
                System.out.println("SEPAL 内置数据库监控表初始化状态: " + isCreated);
            } else {
                System.out.println("警告: 未获取到数据库控制器实例，UI 监测数据将无法保存。");
            }

            // 注册 Sensor (被动模式，期望频率 20Hz)
            Map<String, SensorInfo> supportedSensors = connector.getSupportedSensors();
            if (supportedSensors.containsKey(SENSOR_NAME) && "ON".equals(supportedSensors.get(SENSOR_NAME).getStatus())) {
                connector.registerSensor(SENSOR_NAME, SensorMode.PASSIVE, 20);
                connector.getMsgThread(CmdType.START); // 启动数据接收线程
                System.out.println("已成功订阅 Sensor 数据流。");
            } else {
                System.out.println("未找到 Sensor 或其状态不为 ON，请先启动 SensorWrapper。");
                return;
            }

            // 注册 Actor
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

            try {
                // 在 8081 端口启动一个微型 HTTP 服务器
                HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
                server.createContext("/api/data", new HttpHandler() {
                    @Override
                    public void handle(HttpExchange exchange) throws IOException {
                        // 允许跨域请求 (CORS)，让前端网页能顺利拉取
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Content-Type", "application/json");

                        // 将小车当前最新状态拼接成标准 JSON 格式
                        String jsonResponse = String.format("{\"x\": %.3f, \"y\": %.3f, \"latency\": %d}",
                                app.currentX, app.currentY, app.currentLatency);

                        byte[] responseBytes = jsonResponse.getBytes("UTF-8");
                        exchange.sendResponseHeaders(200, responseBytes.length);
                        OutputStream os = exchange.getResponseBody();
                        os.write(responseBytes);
                        os.close();
                    }
                });
                server.setExecutor(null);
                server.start();
                System.out.println("💡 微型数据 API 已成功启动！前端请求地址: http://localhost:8081/api/data");
            } catch (IOException e) {
                System.out.println("API 服务器启动失败: " + e.getMessage());
            }

            long startTime = System.currentTimeMillis();
            System.out.println("开始计时：SEPAL 跑圈任务启动");

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

            long endTime = System.currentTimeMillis();
            double totalDuration = (endTime - startTime) / 1000.0;
            System.out.printf("任务完成！SEPAL 平台下跑完 5 圈总耗时: %.2f 秒\n", totalDuration);

        } catch (InterruptedException e) {
            System.out.println("任务被中断。");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("正在清理 SEPAL 平台资源...");
            app.sendStopCmd(connector); // 确保小车停下

            // 关闭不变式服务
            InvCheck checker = InvCheck.getInstance();
            if (checker != null && checker.checkGenerated()) {
                checker.saveTo("invs.txt"); // 把本次跑圈生成的安全规则保存下来，留作下次使用
                System.out.println("不变式规则已成功保存至本地！");
            }
            connector.serviceStop(ServiceType.INV);

            // 安全关闭文件记录仪
            if (app.csvWriter != null) {
                app.csvWriter.close();
                System.out.println("数据已安全落盘！请在项目目录下查看 robot_data.csv 文件。");
            }

            connector.cancelAllActors();
            connector.cancelAllSensors();
            connector.getMsgThread(CmdType.STOP);
            connector.unregisterApp(app);
            connector.disConnectPlatform();

            System.out.println("应用已安全退出。");
        }
    }
}
