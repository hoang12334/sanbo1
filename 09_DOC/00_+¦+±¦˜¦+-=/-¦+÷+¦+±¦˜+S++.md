# 服务器配置与部署建议

## 推荐配置

基础模块至少2台服务器，性能2核4G以上，钱包模块根据实际币种多少动态设置，1台以上。

#### 推荐配置一(2台服务器版)

服务器A：部署Nginx服务，Web前端页面,ucenter-api,otc-api,exchange-api,wallet,market,exchange ,cloud，bitcoin

服务器B：部署mysql,mongodb,kafka,redis

#### 推荐配置二(3台服务器版)

服务器A：部署Nginx服务，Web前端页面

服务器B：部署ucenter-api,otc-api,exchange-api,wallet,market,exchange ,cloud，bitcoin

服务器C：部署mysql,mongodb,kafka,redis

#### 推荐配置三(4台服务器版)

服务器A：部署Nginx服务，Web前端页面

服务器B：部署ucenter-api,otc-api,exchange-api,admin-api

服务器C：部署wallet,market,exchange ,cloud，bitcoin

服务器D：部署mysql,mongodb,kafka,redis

#### 推荐配置四(5台服务器版)

服务器A：部署Nginx服务，Web前端页面

服务器B：部署ucenter-api,otc-api,exchange-api

服务器C：部署wallet,market,exchange,cloud

服务器D：部署bitcoin,eth等

服务器E：部署mysql,mongodb,kafka,redis

## 可以集群部署的服务

ucenter-api(IeoEmptionConsumer这个类移至wallet模块),用户个人信息
exchange-api,币币交易下单
otc-api,法币买卖
open-api,开放API（暂无）

## 只能单节点部署的服务

cloud,注册中心
exchange，撮合模块
market，K线模块
wallet,用户钱包记录处理
admin,后台管理
chat,聊天模块



## 总结

生产运维中，推荐以上配置，以下是需要的其他服务资源：

7台服务器

阿里云OSS

阿里云ES

短信下发

腾讯防水墙相关密钥(滑块验证))
