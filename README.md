WiseFido BleconfigforAndriod
20250226 v1.1
  1.sleepboard configure wifi/server, query device history
  2.radar config wifi, server, which can individual configure or together; when disconnet server, query device statu:wifi, history server 
20250305   单实例模式
    for andriod 14, use CountDownLatch  replace sleep(),避免线程被意外中断，特别是安全协商步骤
    20250306
    ScanActivity 扫描到设备后，将 BluetoothDevice 保存到单例中
    ScanActivity 通过 Intent 只传递简单的 DeviceInfo 对象（不包含 originalDevice）
    MainActivity 接收到 DeviceInfo 后，从单例中获取之前保存的 BluetoothDevice 对象
    使用这个 BluetoothDevice 对象进行后续操作
    为避免序列化序列化 BluetoothDevice 对象
    1.首先，简化 DeviceInfo 类，移除 originalDevice 字段：
    2.创建一个单例类来保存原始设备对象：
    3.在 ScanActivity 中，使用这个单例来保存设备对象：
    4.在 MainActivity 中，从单例获取原始设备对象：
    5.在 RadarBleManager 中添加使用原始设备的方法：
    6.当设备不再需要时清理单例
    ./gradlew clean
    ./gradlew build

20250308
    修改Esp BlufiClientImpl.java 解决andriod 14下安全协商bug
    解决办法andriod13(sdk33) default mtu,  andriod14(sdk34) mtu=64

20251107
1. 配网前，先查询一次，确保BLE连接正常
2. 允许删除保存的配置

20251109  v1.1
    1. Radar 指令集统一封装：所有自定义命令整合到 `RadarCommand`，新增预热查询（MAC/UID/运行状态）并在配置前自动执行。
    2. 优化 BLE 配网流程：Wi-Fi/Server 配置前在同一 GATT 会话内完成安全协商并发送轻量查询，提高成功率。
    3. UI 调整：
        - “Status” 重命名为 “Query”，并更新相关按钮/文案。
        - 历史记录区域改为可展开列表，Recent Servers / Recent WiFi 互斥展开。
    4. 历史记录体验：
        - Wi-Fi/Server 列表支持左侧 `Del` 删除按钮，Wi-Fi 密码默认掩码，点击眼睛临时明文展示 3 秒。
        - 历史记录最多保留 5 组，Wi-Fi 密码加密存储并兼容旧数据。
    5. 构建环境升级：所有模块统一使用 Java 17、Kotlin 2.2.0，补充 RecyclerView 等依赖。
