import socket
import time
import json

# 配置参数
JAVA_IP = "127.0.0.1"
SEND_PORT = 9093  # 发给 Java
RECV_PORT = 9092  # 接收 Java 返回
SAMPLES = 10000    # 测试样本量

def run_test(sample_size):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", RECV_PORT))
    sock.settimeout(2.0)

    latencies = []
    lost_packets = 0

    print(f"开始静态环回测试，样本量: {sample_size} 帧...")

    for i in range(sample_size):
        # 1. 构造假数据报文
        msg = {"cmd": "sensory_back", "x": 1.0, "y": 2.0, "yaw": 90.0, "seq": i}
        data = json.dumps(msg).encode('utf-8')

        # 2. 记录极其精确的发送时间 (perf_counter)
        t_send = time.perf_counter()
        sock.sendto(data, (JAVA_IP, SEND_PORT))

        try:
            # 3. 阻塞等待 Java 原路打回
            _, _ = sock.recvfrom(1024)
            t_recv = time.perf_counter()

            # 4. 计算时延 (转化为毫秒)
            rtt_ms = (t_recv - t_send) * 1000
            latencies.append(rtt_ms)
        except socket.timeout:
            lost_packets += 1

        # 休眠 50ms，精确模拟实车 20Hz 的控制与感知频率
        time.sleep(0.05)


    # 打印最终可填入论文的统计结果
    if latencies:
        print("\n=== 测试统计报告 ===")
        print(f"成功接收: {len(latencies)} 包, 丢失: {lost_packets} 包")
        print(f"最小开销: {min(latencies):.2f} ms")
        print(f"最大开销: {max(latencies):.2f} ms")
        print(f"平均开销: {sum(latencies)/len(latencies):.2f} ms")
        print(f"丢包率:   {(lost_packets/sample_size)*100:.2f} %")

    sock.close()

if __name__ == "__main__":
    # 分别测试 1000, 5000, 10000 帧
    run_test(10000)
    print("-" * 30)
    time.sleep(1) # 批次之间稍作休息