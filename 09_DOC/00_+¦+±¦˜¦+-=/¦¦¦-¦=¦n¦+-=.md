# 交易所前端打包部署

1.打包前修改对应后端服务域名或IP

  (1)前端src目录下的main.js中的Vue.prototype.host = "http://127.0.0.1:80";用于切换环境
  (2)后台管理src目录下的service的http.js中的 export const BASEURL = axios.defaults.baseURL = 'http://127.0.0.1:9090'; 用于切换环境

2.打包

node版本大于8.x.x，小于10.9.x，第一次需要装node-sass
执行npm run build命令生成可部署文件

3.部署
  (1)打包后的dist文件上传至服务器
  (2)配置nginx,
        #定义服务器的默认网站根目录位置(前台web端),路径可修改
        root /web/app/web;
        #后台管理页面目录(9090端口对应打包时设置的端口可修改),路径可修改
        listen      9090;
        root /web/app/admin/;
