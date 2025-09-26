# SAE平台插件开发指南

基于 `platform-plugin-template` 模板创建新插件的完整指南

## 🚀 基于模板创建新插件的步骤

### 1. 复制模板项目
```bash
# 复制整个模板项目到新目录
cp -r platform-plugin-template my-new-plugin
cd my-new-plugin
```

### 2. 修改关键配置文件

#### A. 修改 `app/build.gradle` 中的包名和版本
```gradle
// 在 android 块中添加
android {
    // 修改包名
    defaultConfig {
        applicationId "com.yourcompany.yourpluginname"  // 改为你的包名
    }
}

// 修改插件版本
ext.PLUGIN_VERSION = "1.0"  // 你的插件版本
ext.ATAK_VERSION = "1.6.0"  // 保持与SAE版本一致
```

#### B. 修改 `app/src/main/AndroidManifest.xml`
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.yourcompany.yourpluginname">  <!-- 改为你的包名 -->
    
    <application 
        android:label="@string/app_name"  <!-- 改为你的插件名称 -->
        android:description="@string/app_desc">  <!-- 改为你的插件描述 -->
```

#### C. 修改 `app/src/main/assets/plugin.xml`
```xml
<plugin>
    <extension
        type="transapps.maps.plugin.lifecycle.Lifecycle"
        impl="com.yourcompany.yourpluginname.plugin.YourPluginLifecycle"  <!-- 改为你的类名 -->
        singleton="true" />
    
    <extension
        type="transapps.maps.plugin.tool.ToolDescriptor"
        impl="com.yourcompany.yourpluginname.plugin.YourPluginTool"  <!-- 改为你的类名 -->
        singleton="true" />
</plugin>
```

### 3. 重命名Java包结构

#### A. 创建新的包目录结构
```bash
mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin
mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin/component
mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin/proxy
```

#### B. 移动并重命名Java文件
```bash
# 移动文件到新包
mv app/src/main/java/com/saemaps/android/platformplugintemplate/* app/src/main/java/com/yourcompany/yourpluginname/
```

#### C. 修改所有Java文件的包声明
```java
// 在每个Java文件顶部修改包名
package com.yourcompany.yourpluginname.plugin;
// 或者
package com.yourcompany.yourpluginname;
```

### 4. 修改资源文件

#### A. 修改 `app/src/main/res/values/strings.xml`
```xml
<resources>
    <string name="app_name">Your Plugin Name</string>
    <string name="app_desc">Your plugin description</string>
</resources>
```

#### B. 修改图标和资源
- 替换 `ic_launcher.png` 为你的插件图标
- 修改其他资源文件中的文本和样式

### 5. 修改广播接收器标识

#### A. 修改 `PluginTemplateDropDownReceiver.java`
```java
public class YourPluginDropDownReceiver extends DropDownReceiver {
    // 修改广播标识，避免冲突
    public static final String SHOW_PLUGIN = "com.yourcompany.yourpluginname.SHOW_PLUGIN";
    
    // 修改其他标识符
    public static final String PREFIX = "com.yourcompany.yourpluginname.YourPluginDropDownReceiver";
    public static final String REGISTER_MODEL_PLUGIN = PREFIX + ".REGISTER_MODEL_PLUGIN";
    public static final String UNREGISTER_MODEL_PLUGIN = PREFIX + ".UNREGISTER_MODEL_PLUGIN";
}
```

### 6. 修改项目配置

#### A. 修改 `settings.gradle`
```gradle
rootProject.name = 'your-plugin-name'  // 改为你的插件名称
```

#### B. 修改 `build.gradle` 中的项目名称
```gradle
// 在 sourceSets 中修改
setProperty("archivesBaseName", "SAE-Plugin-YourPluginName-" + PLUGIN_VERSION + "-" + getVersionName() + "-" + ATAK_VERSION)
```

### 7. 清理和重新构建

```bash
# 清理项目
./gradlew clean

# 重新构建
./gradlew assembleCivDebug
```

## 📋 关键修改清单

| 配置项 | 原值 | 新值示例 |
|--------|------|----------|
| 包名 | `com.saemaps.android.platformplugintemplate` | `com.yourcompany.yourpluginname` |
| 应用ID | 默认 | `com.yourcompany.yourpluginname` |
| 插件名称 | `Platform Plugin Template` | `Your Plugin Name` |
| 广播标识 | `com.saemaps.android.platformplugintemplate.SHOW_PLUGIN` | `com.yourcompany.yourpluginname.SHOW_PLUGIN` |
| 类名 | `PluginTemplate*` | `YourPlugin*` |
| 项目名称 | `platform-plugin-template` | `your-plugin-name` |

## ⚠️ 注意事项

1. **包名必须唯一** - 确保与现有插件不冲突
2. **广播标识唯一** - 避免广播冲突
3. **类名唯一** - 避免类加载冲突
4. **资源标识唯一** - 避免资源冲突
5. **保持SAE版本一致** - 确保与目标SAE版本兼容

## 🔧 详细修改步骤

### 步骤1: 项目结构重命名

1. **重命名根目录**
   ```bash
   mv platform-plugin-template your-plugin-name
   cd your-plugin-name
   ```

2. **修改settings.gradle**
   ```gradle
   rootProject.name = 'your-plugin-name'
   ```

### 步骤2: 包名修改

1. **创建新包目录结构**
   ```bash
   mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin
   mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin/component
   mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin/proxy
   ```

2. **移动Java文件**
   ```bash
   # 移动所有Java文件到新包
   find app/src/main/java/com/saemaps/android/platformplugintemplate -name "*.java" -exec mv {} app/src/main/java/com/yourcompany/yourpluginname/ \;
   ```

3. **修改所有Java文件的包声明**
   - 将 `package com.saemaps.android.platformplugintemplate.*;` 替换为 `package com.yourcompany.yourpluginname.*;`
   - 将 `package com.saemaps.android.platformplugintemplate.plugin.*;` 替换为 `package com.yourcompany.yourpluginname.plugin.*;`

### 步骤3: 配置文件修改

1. **AndroidManifest.xml**
   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
       package="com.yourcompany.yourpluginname">
   ```

2. **plugin.xml**
   ```xml
   <plugin>
       <extension
           type="transapps.maps.plugin.lifecycle.Lifecycle"
           impl="com.yourcompany.yourpluginname.plugin.YourPluginLifecycle"
           singleton="true" />
       
       <extension
           type="transapps.maps.plugin.tool.ToolDescriptor"
           impl="com.yourcompany.yourpluginname.plugin.YourPluginTool"
           singleton="true" />
   </plugin>
   ```

3. **build.gradle**
   ```gradle
   android {
       defaultConfig {
           applicationId "com.yourcompany.yourpluginname"
       }
   }
   
   ext.PLUGIN_VERSION = "1.0"
   ext.ATAK_VERSION = "1.6.0"
   ```

### 步骤4: 资源文件修改

1. **strings.xml**
   ```xml
   <resources>
       <string name="app_name">Your Plugin Name</string>
       <string name="app_desc">Your plugin description</string>
   </resources>
   ```

2. **替换图标**
   - 将 `ic_launcher.png` 替换为你的插件图标

### 步骤5: 代码修改

1. **重命名类名**
   - `PluginTemplateLifecycle` → `YourPluginLifecycle`
   - `PluginTemplateTool` → `YourPluginTool`
   - `PluginTemplateDropDownReceiver` → `YourPluginDropDownReceiver`
   - `PluginTemplateMapComponent` → `YourPluginMapComponent`

2. **修改广播标识**
   ```java
   public static final String SHOW_PLUGIN = "com.yourcompany.yourpluginname.SHOW_PLUGIN";
   public static final String PREFIX = "com.yourcompany.yourpluginname.YourPluginDropDownReceiver";
   ```

3. **修改常量标识**
   ```java
   public static final String REGISTER_MODEL_PLUGIN = PREFIX + ".REGISTER_MODEL_PLUGIN";
   public static final String UNREGISTER_MODEL_PLUGIN = PREFIX + ".UNREGISTER_MODEL_PLUGIN";
   ```

## 🚀 快速开始脚本

创建一个自动化脚本来简化上述过程：

```bash
#!/bin/bash
# create_new_plugin.sh

OLD_PACKAGE="com.saemaps.android.platformplugintemplate"
NEW_PACKAGE="com.yourcompany.yourpluginname"
OLD_PROJECT="platform-plugin-template"
NEW_PROJECT="your-plugin-name"

# 复制项目
cp -r $OLD_PROJECT $NEW_PROJECT
cd $NEW_PROJECT

# 修改settings.gradle
sed -i "s/$OLD_PROJECT/$NEW_PROJECT/g" settings.gradle

# 修改build.gradle
sed -i "s/$OLD_PACKAGE/$NEW_PACKAGE/g" app/build.gradle

# 修改AndroidManifest.xml
sed -i "s/$OLD_PACKAGE/$NEW_PACKAGE/g" app/src/main/AndroidManifest.xml

# 修改plugin.xml
sed -i "s/$OLD_PACKAGE/$NEW_PACKAGE/g" app/src/main/assets/plugin.xml

# 创建新包目录
mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin
mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin/component
mkdir -p app/src/main/java/com/yourcompany/yourpluginname/plugin/proxy

# 移动Java文件
find app/src/main/java/com/saemaps/android/platformplugintemplate -name "*.java" -exec mv {} app/src/main/java/com/yourcompany/yourpluginname/ \;

# 修改Java文件包名
find app/src/main/java/com/yourcompany/yourpluginname -name "*.java" -exec sed -i "s/$OLD_PACKAGE/$NEW_PACKAGE/g" {} \;

echo "插件创建完成！请手动修改以下内容："
echo "1. 修改 strings.xml 中的插件名称和描述"
echo "2. 替换 ic_launcher.png 图标"
echo "3. 重命名Java类名"
echo "4. 修改广播标识符"
```

## 📝 总结

通过以上步骤，您可以基于 `platform-plugin-template` 模板创建新的SAE平台插件，确保：

- ✅ 包名唯一，避免冲突
- ✅ 广播标识唯一，避免冲突
- ✅ 类名唯一，避免冲突
- ✅ 资源标识唯一，避免冲突
- ✅ 与现有插件共存

记住：**永远不要修改原始模板**，总是基于模板创建新的插件项目！
