#!/bin/bash

# 斗地主模组自动迭代脚本
# 使用方法: ./AUTO_ITERATE.sh [迭代描述]

set -e

PROJECT_DIR="C:/Users/Admin/Documents/Playground/doudizhu-paper"
ITERATIONS_DIR="$PROJECT_DIR/iterations"

# 获取当前版本
CURRENT_VERSION=$(grep -oP 'version = "\K[^"]+' "$PROJECT_DIR/build.gradle.kts")
echo "当前版本: $CURRENT_VERSION"

# 解析版本号
IFS='.' read -ra VERSION_PARTS <<< "$CURRENT_VERSION"
MAJOR=${VERSION_PARTS[0]}
MINOR=${VERSION_PARTS[1]}
PATCH=${VERSION_PARTS[2]}

# 增加修订号
NEW_PATCH=$((PATCH + 1))
NEW_VERSION="$MAJOR.$MINOR.$NEW_PATCH"
echo "新版本: $NEW_VERSION"

# 创建迭代目录
VERSION_DIR="$ITERATIONS_DIR/v$NEW_VERSION"
mkdir -p "$VERSION_DIR"

echo "创建迭代目录: $VERSION_DIR"

# 复制关键文件
echo "复制项目文件..."
cp -r "$PROJECT_DIR/src" "$VERSION_DIR/"
cp "$PROJECT_DIR/build.gradle.kts" "$VERSION_DIR/"
cp "$PROJECT_DIR/README.md" "$VERSION_DIR/"
cp "$PROJECT_DIR/settings.gradle.kts" "$VERSION_DIR/"

# 创建更新日志模板
cat > "$VERSION_DIR/CHANGELOG.md" << EOF
# 版本 $NEW_VERSION 更新日志

## 更新日期
$(date +"%Y-%m-%d %H:%M:%S")

## 迭代描述
$1

## 更新内容

### 🚀 新功能
- 

### 🔧 改进优化
- 

### 🐛 Bug修复
- 

### 📚 文档更新
- 

## 技术变更
- 版本号更新: $CURRENT_VERSION → $NEW_VERSION

## 测试结果
- [ ] 功能测试通过
- [ ] 兼容性测试通过
- [ ] 性能测试通过

## 备注
EOF

echo "更新日志已创建: $VERSION_DIR/CHANGELOG.md"

# 更新项目版本号
echo "更新项目版本号..."
sed -i "s/version = \"$CURRENT_VERSION\"/version = \"$NEW_VERSION\"/" "$PROJECT_DIR/build.gradle.kts"

echo "✅ 迭代准备完成!"
echo "版本: $NEW_VERSION"
echo "目录: $VERSION_DIR"
echo ""
echo "下一步:"
echo "1. 实现改进功能"
echo "2. 更新 CHANGELOG.md"
echo "3. 测试验证"
echo "4. 提交更改"