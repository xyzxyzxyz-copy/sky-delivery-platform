# Sky Delivery Platform · 苍穹外卖服务平台

基于 Spring Boot 的外卖系统后端，为商家管理端与微信小程序提供 API，覆盖商品管理、购物车、订单履约、经营统计和实时消息。

## 技术栈

Spring Boot 2.7.3、MyBatis、MySQL、Redis、Spring Cache、PageHelper、JWT、WebSocket、Spring Task、Knife4j、Apache POI、阿里云 OSS、微信接口和百度地图。

## 工程结构

```text
sky-common/    通用结果、异常、常量、配置属性和工具
sky-pojo/      实体、DTO、VO
sky-server/    接口、业务、持久层、缓存、定时任务与消息
```

## 功能

| 模块 | 功能 |
| --- | --- |
| 员工与认证 | 员工登录、增改查、启停用、管理端/用户端 JWT 校验 |
| 商品 | 分类、菜品与口味、套餐与套餐明细、上下架、OSS 图片上传 |
| 用户 | 微信登录、地址簿、默认地址、购物车 |
| 订单 | 下单、支付、历史订单、详情、取消、复购、催单 |
| 商家订单 | 条件分页搜索、状态统计、接单、拒单、取消、派送、完成 |
| 配送 | 百度地图地址解析和驾车路线距离校验，配送范围 5 公里 |
| 实时处理 | WebSocket 新订单/催单消息、定时处理超时订单 |
| 经营分析 | 工作台概览、营业额、用户/订单统计、销量 Top10、Excel 导出 |
| 缓存 | 商品缓存、营业状态、订单详情空值缓存、互斥重建、随机过期 |

## 本地运行

1. 准备 JDK 17、Maven、MySQL 和 Redis。
2. 创建业务数据库并导入配套教学项目的表结构。此仓库不包含真实数据库备份、用户数据或订单数据，未附初始化 SQL。
3. 将 `sky-server/src/main/resources/application-dev.example.yml` 复制为同目录的 `application-dev.yml`。
4. 设置数据库和 Redis 环境变量；按需设置 OSS、微信、百度地图配置。
5. 公开版本的 JWT 密钥通过 `JWT_ADMIN_SECRET`、`JWT_USER_SECRET` 提供，请分别使用足够长的随机值。

```shell
mvn test
mvn -pl sky-server -am package -DskipTests
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar
```

默认端口 `8080`，接口文档为 `http://localhost:8080/doc.html`。

## 配置与当前实现边界

- `WECHAT_APP_ID` / `WECHAT_APP_SECRET`：小程序登录。
- `sky.wechat.pay-enabled` 默认 `false`，本地使用模拟支付。真实支付需要另外配置商户号、证书、API V3 Key 与 HTTPS 回调，并进行真实环境联调。
- `SKY_SHOP_ADDRESS`：实际门店地址；`BAIDU_MAP_AK`：百度地图服务端 AK。当前未配置 AK 时会跳过配送范围校验。
- `sky.order-cache`：订单详情默认缓存 30～40 分钟，不存在的订单缓存 2 分钟。订单变更后删除详情缓存，用户读取缓存后继续校验归属。
- 条件分页、订单状态统计和报表仍直接访问数据库。Redis 故障时详情查询降级访问数据库，需要结合流量容量安排部署。
- 缓存测试使用 Mockito，不等同于真实 Redis 并发压测；互斥锁采用固定租期，等待超时会回源。随机 TTL 用于缓解同时失效，不涵盖 Redis 整体故障的高可用部署。

## 公开发布说明

本公开仓库从当前项目代码按依赖与业务模块分批整理，提交使用整理时的实际时间。原始开发仓库的历史未导入，以避免其中曾提交的本地配置与凭据重新公开。

本地环境配置、密码、证书、IDE 文件、编译产物和数据库备份不应提交。配置示例只使用环境变量占位符。项目用于学习与功能展示，生产部署前仍需验证支付、退款、鉴权、状态并发和缓存一致性。
