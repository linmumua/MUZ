DouDizhuPaper 0.1.4 Release Bundle

This bundle is organized as follows:

01-plugin
- Put `doudizhu-paper-0.1.4.jar` into your server `plugins/` folder.

02-resourcepack
- This is the normal client resource pack zip.
- Send `doudizhu-paper-resourcepack-0.1.4.zip` to players or host it for automatic download.

03-craftengine
- This is the CraftEngine bundle zip.
- Use it if your server runs CraftEngine.

Quick Chinese guide:

1. `01-plugin` 里的 jar 放进服务端 `plugins` 文件夹。
2. `02-resourcepack` 里的 zip 是普通材质包，给客户端使用。
3. `03-craftengine` 里的 zip 是 CraftEngine 资源包整合包。
4. 在游戏里使用 `/ddz place <牌桌名>` 可以直接放出实体桌椅牌桌。
5. 如果服务器已经安装 CraftEngine，插件启用后会自动检查并导出 bundle 到其 `resources/doudizhupaper` 目录。
6. 当你执行 `/ce reload` 时，插件会在命令执行前再次检查并导出 bundle。
7. 使用 `/ddz addbot` 可以给当前牌桌添加一个简单机器人。
8. `config.yml` 里可以控制是否显示牌面全息字符标签，以及是否只在重复点数牌上显示。
9. 如果只是先测试基础玩法，最少只需要：
   - 插件 jar
   - 普通材质包 zip

Main paths in the original project:
- build/libs/doudizhu-paper-0.1.4.jar
- build/distributions/doudizhu-paper-resourcepack-0.1.4.zip
- build/distributions/doudizhu-paper-craftengine-0.1.4.zip
