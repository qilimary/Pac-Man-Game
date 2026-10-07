#!/usr/bin/env bash
# Place this file and GameView.java beside build.yml in .github/workflows/.
# Run from any directory; the project is always generated at the repository root.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$script_dir/../.."
test -s "$script_dir/GameView.java"

mkdir -p generated-game/app/src/main/java/com/qi/xingdoumaze
mkdir -p generated-game/app/src/main/res/values
mkdir -p generated-game/app/src/main/res/drawable
mkdir -p generated-game/app/src/main/res/drawable-nodpi
mkdir -p generated-game/tools/com/qi/xingdoumaze

cat > generated-game/settings.gradle <<'SETTINGS'
import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = '星豆迷宫'
include ':app'
SETTINGS

cat > generated-game/build.gradle <<'ROOT_GRADLE'
plugins {
    id 'com.android.application' version '9.2.1' apply false
}
ROOT_GRADLE

cat > generated-game/gradle.properties <<'GRADLE_PROPERTIES'
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.daemon=false
org.gradle.workers.max=2
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
GRADLE_PROPERTIES

cat > generated-game/app/build.gradle <<'APP_GRADLE'
plugins {
    id 'com.android.application'
}

android {
    namespace = 'com.qi.xingdoumaze'
    compileSdk = 36

    defaultConfig {
        applicationId = 'com.qi.xingdoumaze'
        minSdk = 24
        targetSdk = 36
        versionCode = 21
        versionName = '1.20.0'
    }

    buildTypes {
        debug {
            minifyEnabled = false
        }
        release {
            minifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    testOptions {
        unitTests.includeAndroidResources = true
        unitTests.all {
            maxHeapSize = "2g"
            systemProperty "robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation "junit:junit:4.13.2"
    testImplementation "org.robolectric:robolectric:4.16.1"
    testImplementation "androidx.test:core:1.6.1"
}
APP_GRADLE

cat > generated-game/app/src/main/AndroidManifest.xml <<'MANIFEST'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="true"
        android:hardwareAccelerated="true"
        android:icon="@drawable/app_icon"
        android:roundIcon="@drawable/app_icon"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/AppTheme"
        android:usesCleartextTraffic="false">
        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:exported="true"
            android:enableOnBackInvokedCallback="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
MANIFEST

cat > generated-game/app/src/main/res/values/strings.xml <<'STRINGS'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">星豆迷宫</string>
</resources>
STRINGS

cat > generated-game/app/src/main/res/values/styles.xml <<'STYLES'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowFullscreen">true</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:fontFamily">sans</item>
        <item name="android:colorAccent">#FFC857</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:navigationBarColor">#000000</item>
        <item name="android:windowBackground">#10052E</item>
    </style>
</resources>
STYLES

cat > generated-game/app/src/main/res/drawable/ic_launcher.xml <<'ICON'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#15102E" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#5B2D90" android:pathData="M8,8h92a8,8 0,0 1,8 8v76a8,8 0,0 1,-8 8h-92a8,8 0,0 1,-8 -8v-76a8,8 0,0 1,8 -8z" />
    <path android:fillColor="#FFD43B" android:pathData="M43,20A34,34 0,1 0,75 66L43,54L75,42A34,34 0,0 0,43 20z" />
    <path android:fillColor="#241B35" android:pathData="M42,35a4,4 0,1 0,0.1 0z" />
    <path android:fillColor="#FFC857" android:pathData="M86,46l2.7,5.5l6.1,0.9l-4.4,4.3l1,6.1l-5.4,-2.9l-5.4,2.9l1,-6.1l-4.4,-4.3l6.1,-0.9z" />
    <path android:fillColor="#6CE6FF" android:pathData="M84,74a5,5 0,1 0,0.1 0z" />
    <path android:fillColor="#FF78B7" android:pathData="M96,72a3.5,3.5 0,1 0,0.1 0z" />
</vector>
ICON

# 同一张用户图片同时用作首页头像与 Android 应用图标。
avatar_source=""
for candidate in "chidouren.png" "avatar.png" "头像.png" "chidouren.jpg" "avatar.jpg" "头像.jpg"; do
  if [[ -f "$candidate" ]]; then
    avatar_source="$candidate"
    break
  fi
done
if [[ -z "$avatar_source" ]]; then
  avatar_source="$(find . -maxdepth 2 -type f               \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \)               -not -path './generated-game/*' | head -n 1 || true)"
fi
if [[ -n "$avatar_source" && -f "$avatar_source" ]]; then
  mime_type="$(file -b --mime-type "$avatar_source" || true)"
  case "$mime_type" in
    image/jpeg) image_ext="jpg" ;;
    image/webp) image_ext="webp" ;;
    *) image_ext="png" ;;
  esac
  cp "$avatar_source" "generated-game/app/src/main/res/drawable-nodpi/app_avatar.${image_ext}"
  cp "$avatar_source" "generated-game/app/src/main/res/drawable-nodpi/app_icon.${image_ext}"
  echo "已使用头像与应用图标文件：$avatar_source（$mime_type）"
else
  echo "未找到上传图片，使用内置备用头像与图标"
  cat > generated-game/app/src/main/res/drawable/app_avatar.xml <<'APP_AVATAR'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#5B2D90" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#FFD43B" android:pathData="M43,20A34,34 0,1 0,75 66L43,54L75,42A34,34 0,0 0,43 20z" />
    <path android:fillColor="#241B35" android:pathData="M42,35a4,4 0,1 0,0.1 0z" />
</vector>
APP_AVATAR
  cp generated-game/app/src/main/res/drawable/ic_launcher.xml generated-game/app/src/main/res/drawable/app_icon.xml
fi

cat > generated-game/app/src/main/java/com/qi/xingdoumaze/MainActivity.java <<'MAIN_ACTIVITY'
package com.qi.xingdoumaze;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedDispatcher;

public final class MainActivity extends Activity {
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gameView = new GameView(this);
        setContentView(gameView);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        if (gameView == null || !gameView.handleBack()) finish();
                    });
        }
        // 竖屏已由 Manifest 固定，避免部分 Android 16 设备重复请求方向时抛异常。
        try {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.BLACK);
            enterImmersiveMode();
        } catch (RuntimeException | LinkageError ignored) {
            // 系统栏定制失败不应阻止游戏启动。
        }
    }

    private void enterImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decor = getWindow().getDecorView();
                if (decor != null) {
                    decor.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 部分厂商系统不完整实现沉浸式 API，安全降级为普通全屏。
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (gameView != null) gameView.resumeFromSystem();
    }

    @Override
    protected void onPause() {
        if (gameView != null) gameView.pauseFromSystem();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (gameView == null || !gameView.handleBack()) super.onBackPressed();
    }
}
MAIN_ACTIVITY

cat > generated-game/app/src/main/java/com/qi/xingdoumaze/MazeGenerator.java <<'MAZE_GENERATOR'
package com.qi.xingdoumaze;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 先生成连通迷宫，再按主题增加结构。所有地图保持25×29完整格数，
 * 每局仅通过等比例缩放产生轻微视觉尺寸差异，不再裁掉底部行。
 */
final class MazeGenerator {
    static final int COLS = 25;
    static final int ROWS = 29;
    static final int TUNNEL_NONE = 0;
    static final int TUNNEL_HORIZONTAL = 1;
    static final int TUNNEL_VERTICAL = 2;

    static final class Result {
        final boolean[][] walls;
        final int playerX;
        final int playerY;
        final int[][] enemySpawns;
        final int companionX;
        final int companionY;
        final int activeRows;
        final long signature;
        final int tunnelAxis;
        final int tunnelCoordinate;
        final float visualScale;

        Result(boolean[][] walls, int playerX, int playerY, int[][] enemySpawns,
               int companionX, int companionY, int activeRows, long signature,
               int tunnelAxis, int tunnelCoordinate, float visualScale) {
            this.walls = walls;
            this.playerX = playerX;
            this.playerY = playerY;
            this.enemySpawns = enemySpawns;
            this.companionX = companionX;
            this.companionY = companionY;
            this.activeRows = activeRows;
            this.signature = signature;
            this.tunnelAxis = tunnelAxis;
            this.tunnelCoordinate = tunnelCoordinate;
            this.visualScale = visualScale;
        }
    }

    private MazeGenerator() {}

    static Result generate(int theme, long seed) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Random random = new Random(seed + 0x9E3779B97F4A7C15L * attempt);
            boolean[][] walls = filledWalls();
            int tunnelAxis = theme == 1 ? TUNNEL_HORIZONTAL
                    : theme == 5 ? (random.nextBoolean() ? TUNNEL_HORIZONTAL : TUNNEL_VERTICAL)
                    : TUNNEL_NONE;
            int tunnelCoordinate = tunnelAxis == TUNNEL_HORIZONTAL ? 13
                    : tunnelAxis == TUNNEL_VERTICAL ? 12 : -1;

            if (theme == 1) {
                carveClassicSymmetric(walls, random);
            } else if (theme == 5) {
                carveFactorySymmetric(walls, random, tunnelAxis);
            } else {
                carvePerfectMaze(walls, random);
                addLoops(walls, random, theme == 2 ? 44 : 30 + random.nextInt(13));
            }

            carveSpawnSafety(walls);
            if (theme == 0) carveClownArena(walls);
            if (theme == 1) carveClassicDetails(walls, random);
            if (theme == 2) carveOceanEscapeGrid(walls);
            if (theme == 3) carveSpringSquares(walls, random);
            if (theme == 4) carveGalaxyCore(walls);
            if (theme == 5) carveFactoryDetails(walls, random, tunnelAxis);

            sealBorder(walls, tunnelAxis, tunnelCoordinate);
            pruneUnreachable(walls, 1, ROWS - 2);
            carveSpawnSafety(walls);
            // 主题通道可能被出生安全区后的整理影响，再强制恢复一次。
            if (theme == 1) carveClassicTunnel(walls);
            if (theme == 5) carveFactoryTunnel(walls, tunnelAxis);
            sealBorder(walls, tunnelAxis, tunnelCoordinate);

            if (countFloors(walls) < 255 || !allFloorsConnected(walls, 1, ROWS - 2)) continue;
            if (theme == 2 && maxOceanEscapeDistance(walls) > 20) continue;

            int[][] spawns = chooseEnemySpawns(walls, theme, random, ROWS);
            int[] companion = nearestFloor(walls, 4, ROWS - 2, null);
            float visualScale = random.nextBoolean() ? 1.000f : 1.032f;
            return new Result(walls, 1, ROWS - 2, spawns,
                    companion[0], companion[1], ROWS, signature(walls, ROWS),
                    tunnelAxis, tunnelCoordinate, visualScale);
        }

        boolean[][] fallback = filledWalls();
        carveFallback(fallback);
        Random fallbackRandom = new Random(seed);
        int tunnelAxis = theme == 1 ? TUNNEL_HORIZONTAL
                : theme == 5 ? (fallbackRandom.nextBoolean() ? TUNNEL_HORIZONTAL : TUNNEL_VERTICAL)
                : TUNNEL_NONE;
        int tunnelCoordinate = tunnelAxis == TUNNEL_HORIZONTAL ? 13
                : tunnelAxis == TUNNEL_VERTICAL ? 12 : -1;
        if (theme == 1) carveClassicTunnel(fallback);
        if (theme == 5) {
            carveFactoryDetails(fallback, fallbackRandom, tunnelAxis);
            carveFactoryTunnel(fallback, tunnelAxis);
        }
        sealBorder(fallback, tunnelAxis, tunnelCoordinate);
        carveSpawnSafety(fallback);
        int[][] spawns = chooseEnemySpawns(fallback, theme, fallbackRandom, ROWS);
        int[] companion = nearestFloor(fallback, 4, ROWS - 2, null);
        return new Result(fallback, 1, ROWS - 2, spawns,
                companion[0], companion[1], ROWS, signature(fallback, ROWS),
                tunnelAxis, tunnelCoordinate, 1.016f);
    }

    private static boolean[][] filledWalls() {
        boolean[][] out = new boolean[ROWS][COLS];
        for (boolean[] row : out) Arrays.fill(row, true);
        return out;
    }

    private static void carvePerfectMaze(boolean[][] walls, Random random) {
        final int logicalW = (COLS - 1) / 2;
        final int logicalH = (ROWS - 1) / 2;
        boolean[][] visited = new boolean[logicalH][logicalW];
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        int sx = random.nextInt(logicalW);
        int sy = random.nextInt(logicalH);
        visited[sy][sx] = true;
        walls[sy * 2 + 1][sx * 2 + 1] = false;
        stack.push(new int[]{sx, sy});

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            List<int[]> choices = new ArrayList<>(4);
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int ny = cur[1] + d[1];
                if (nx >= 0 && nx < logicalW && ny >= 0 && ny < logicalH && !visited[ny][nx]) {
                    choices.add(new int[]{nx, ny, d[0], d[1]});
                }
            }
            if (choices.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] next = choices.get(random.nextInt(choices.size()));
            int cx = cur[0] * 2 + 1;
            int cy = cur[1] * 2 + 1;
            walls[cy + next[3]][cx + next[2]] = false;
            walls[next[1] * 2 + 1][next[0] * 2 + 1] = false;
            visited[next[1]][next[0]] = true;
            stack.push(new int[]{next[0], next[1]});
        }
    }

    private static void carveClassicSymmetric(boolean[][] walls, Random random) {
        final int logicalW = 6;
        final int logicalH = (ROWS - 1) / 2;
        boolean[][] visited = new boolean[logicalH][logicalW];
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        int sx = random.nextInt(logicalW);
        int sy = random.nextInt(logicalH);
        visited[sy][sx] = true;
        walls[sy * 2 + 1][sx * 2 + 1] = false;
        stack.push(new int[]{sx, sy});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            List<int[]> choices = new ArrayList<>(4);
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int ny = cur[1] + d[1];
                if (nx >= 0 && nx < logicalW && ny >= 0 && ny < logicalH && !visited[ny][nx]) {
                    choices.add(new int[]{nx, ny, d[0], d[1]});
                }
            }
            if (choices.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] n = choices.get(random.nextInt(choices.size()));
            int cx = cur[0] * 2 + 1;
            int cy = cur[1] * 2 + 1;
            walls[cy + n[3]][cx + n[2]] = false;
            walls[n[1] * 2 + 1][n[0] * 2 + 1] = false;
            visited[n[1]][n[0]] = true;
            stack.push(new int[]{n[0], n[1]});
        }

        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x <= 11; x++) walls[y][COLS - 1 - x] = walls[y][x];
        }
        int[] bridges = {3, 9, 15, 21, 27};
        int skipped = random.nextInt(bridges.length - 1);
        for (int i = 0; i < bridges.length; i++) {
            if (i == skipped) continue;
            int y = bridges[i];
            walls[y][11] = false;
            walls[y][12] = false;
            walls[y][13] = false;
        }
        addLoops(walls, random, 18);
    }

    private static void carveFactorySymmetric(boolean[][] walls, Random random, int tunnelAxis) {
        carveClassicSymmetric(walls, random);
        // 工厂主干道：保持经典吃豆人式左右对称，同时加入直线传送带通道。
        int[] rows = {5, 9, 19, 23};
        for (int y : rows) {
            for (int x = 2; x < COLS - 2; x++) {
                if ((x + y) % 7 != 0) walls[y][x] = false;
            }
        }
        for (int y = 2; y < ROWS - 2; y++) {
            if ((y & 3) != 0) {
                walls[y][4] = false;
                walls[y][20] = false;
            }
        }
        addLoops(walls, random, 15);
        carveFactoryTunnel(walls, tunnelAxis);
    }

    private static void addLoops(boolean[][] walls, Random random, int wanted) {
        List<int[]> candidates = new ArrayList<>();
        for (int y = 1; y < ROWS - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (!walls[y][x]) continue;
                boolean horizontal = !walls[y][x - 1] && !walls[y][x + 1];
                boolean vertical = !walls[y - 1][x] && !walls[y + 1][x];
                if (horizontal ^ vertical) candidates.add(new int[]{x, y});
            }
        }
        Collections.shuffle(candidates, random);
        for (int i = 0; i < Math.min(wanted, candidates.size()); i++) {
            int[] p = candidates.get(i);
            walls[p[1]][p[0]] = false;
        }
    }

    private static void carveSpawnSafety(boolean[][] walls) {
        carveSpawnSafety(walls, ROWS);
    }

    private static void carveSpawnSafety(boolean[][] walls, int activeRows) {
        int y = activeRows - 2;
        for (int x = 1; x <= 6; x++) walls[y][x] = false;
        for (int x = 1; x <= 5; x++) walls[y - 2][x] = false;
        for (int yy = y - 2; yy <= y; yy++) {
            walls[yy][1] = false;
            walls[yy][5] = false;
        }
    }

    private static void carveClownArena(boolean[][] walls) {
        for (int y = 12; y <= 16; y++) {
            for (int x = 10; x <= 14; x++) walls[y][x] = false;
        }
        for (int y = 13; y <= 15; y++) {
            for (int x = 11; x <= 13; x++) walls[y][x] = true;
        }
        for (int x = 8; x <= 16; x++) {
            walls[12][x] = false;
            walls[16][x] = false;
        }
        for (int y = 10; y <= 18; y++) {
            walls[y][10] = false;
            walls[y][14] = false;
        }
    }

    private static void carveClassicDetails(boolean[][] walls, Random random) {
        for (int y = 12; y <= 16; y++) {
            for (int x = 9; x <= 15; x++) walls[y][x] = false;
        }
        walls[12][9] = walls[12][10] = walls[12][14] = walls[12][15] = true;
        walls[16][9] = walls[16][10] = walls[16][14] = walls[16][15] = true;
        carveClassicTunnel(walls);
        for (int i = 0; i < 4; i++) {
            int y = 3 + random.nextInt(12) * 2;
            walls[y][12] = false;
        }
    }

    private static void carveClassicTunnel(boolean[][] walls) {
        int tunnelY = 13;
        for (int x = 0; x <= 9; x++) walls[tunnelY][x] = false;
        for (int x = 15; x < COLS; x++) walls[tunnelY][x] = false;
    }

    private static void carveFactoryDetails(boolean[][] walls, Random random, int tunnelAxis) {
        // 中央机器人装配舱：有外壳、内部活动区和一至两个出口。
        for (int y = 11; y <= 17; y++) {
            for (int x = 8; x <= 16; x++) walls[y][x] = false;
        }
        for (int x = 8; x <= 16; x++) {
            walls[11][x] = true;
            walls[17][x] = true;
        }
        for (int y = 11; y <= 17; y++) {
            walls[y][8] = true;
            walls[y][16] = true;
        }
        walls[11][12] = false;
        walls[10][12] = false;
        if (tunnelAxis == TUNNEL_VERTICAL) {
            walls[17][12] = false;
            walls[18][12] = false;
        }

        // 左右各布置一组“机器岛”，轮廓固定，周边支路随机。
        int[][] blocks = {{3, 3, 7, 6}, {17, 3, 21, 6}, {3, 21, 7, 24}, {17, 21, 21, 24}};
        for (int[] b : blocks) {
            for (int x = b[0]; x <= b[2]; x++) {
                walls[b[1]][x] = true;
                walls[b[3]][x] = true;
            }
            for (int y = b[1]; y <= b[3]; y++) {
                walls[y][b[0]] = true;
                walls[y][b[2]] = true;
            }
            int doorX = random.nextBoolean() ? b[0] + 1 : b[2] - 1;
            walls[b[1]][doorX] = false;
        }
        carveFactoryTunnel(walls, tunnelAxis);
    }

    private static void carveFactoryTunnel(boolean[][] walls, int tunnelAxis) {
        if (tunnelAxis == TUNNEL_HORIZONTAL) {
            int y = 13;
            for (int x = 0; x < COLS; x++) walls[y][x] = false;
        } else if (tunnelAxis == TUNNEL_VERTICAL) {
            int x = 12;
            for (int y = 0; y < ROWS; y++) walls[y][x] = false;
        }
    }

    private static void carveOceanEscapeGrid(boolean[][] walls) {
        int[] vertical = {3, 9, 15, 21};
        for (int x : vertical) {
            for (int y = 1; y < ROWS - 1; y++) walls[y][x] = false;
        }
        int[] horizontal = {7, 13, 19, 23};
        for (int y : horizontal) {
            for (int x = 1; x < COLS - 1; x++) walls[y][x] = false;
        }
    }

    private static void carveSpringSquares(boolean[][] walls, Random random) {
        int[][] centers = {{6, 7}, {18, 7}, {6, 19}, {18, 19}};
        for (int[] c : centers) {
            if (random.nextBoolean()) {
                for (int x = c[0] - 2; x <= c[0] + 2; x++) {
                    walls[c[1] - 2][x] = false;
                    walls[c[1] + 2][x] = false;
                }
                for (int y = c[1] - 2; y <= c[1] + 2; y++) {
                    walls[y][c[0] - 2] = false;
                    walls[y][c[0] + 2] = false;
                }
            }
        }
    }

    private static void carveGalaxyCore(boolean[][] walls) {
        for (int x = 8; x <= 16; x++) walls[14][x] = false;
        for (int y = 10; y <= 18; y++) walls[y][12] = false;
        for (int y = 12; y <= 16; y++) {
            for (int x = 10; x <= 14; x++) walls[y][x] = false;
        }
    }

    private static void sealBorder(boolean[][] walls, int tunnelAxis, int coordinate) {
        for (int x = 0; x < COLS; x++) {
            walls[0][x] = true;
            walls[ROWS - 1][x] = true;
        }
        for (int y = 0; y < ROWS; y++) {
            walls[y][0] = true;
            walls[y][COLS - 1] = true;
        }
        if (tunnelAxis == TUNNEL_HORIZONTAL) {
            walls[coordinate][0] = false;
            walls[coordinate][COLS - 1] = false;
        } else if (tunnelAxis == TUNNEL_VERTICAL) {
            walls[0][coordinate] = false;
            walls[ROWS - 1][coordinate] = false;
        }
    }

    private static void pruneUnreachable(boolean[][] walls, int sx, int sy) {
        boolean[][] seen = flood(walls, sx, sy);
        for (int y = 1; y < ROWS - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (!walls[y][x] && !seen[y][x]) walls[y][x] = true;
            }
        }
    }

    private static boolean[][] flood(boolean[][] walls, int sx, int sy) {
        boolean[][] seen = new boolean[ROWS][COLS];
        if (walls[sy][sx]) return seen;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sy});
        seen[sy][sx] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] p = q.removeFirst();
            for (int[] d : dirs) {
                int nx = p[0] + d[0];
                int ny = p[1] + d[1];
                if (nx < 0 || nx >= COLS || ny < 0 || ny >= ROWS || seen[ny][nx] || walls[ny][nx]) continue;
                seen[ny][nx] = true;
                q.addLast(new int[]{nx, ny});
            }
        }
        return seen;
    }

    private static boolean allFloorsConnected(boolean[][] walls, int sx, int sy) {
        boolean[][] seen = flood(walls, sx, sy);
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) if (!walls[y][x] && !seen[y][x]) return false;
        }
        return true;
    }

    static int countFloors(boolean[][] walls) {
        int count = 0;
        for (boolean[] row : walls) for (boolean wall : row) if (!wall) count++;
        return count;
    }

    static int maxOceanEscapeDistance(boolean[][] walls) {
        int safeLastRow = (int) Math.floor((ROWS - 1) * 0.40f);
        int[][] dist = new int[ROWS][COLS];
        for (int[] row : dist) Arrays.fill(row, -1);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int y = 1; y <= safeLastRow; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (!walls[y][x]) {
                    dist[y][x] = 0;
                    q.addLast(new int[]{x, y});
                }
            }
        }
        int max = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] p = q.removeFirst();
            max = Math.max(max, dist[p[1]][p[0]]);
            for (int[] d : dirs) {
                int nx = p[0] + d[0];
                int ny = p[1] + d[1];
                if (nx < 0 || nx >= COLS || ny < 0 || ny >= ROWS || walls[ny][nx] || dist[ny][nx] >= 0) continue;
                dist[ny][nx] = dist[p[1]][p[0]] + 1;
                q.addLast(new int[]{nx, ny});
            }
        }
        return max;
    }

    private static int[][] chooseEnemySpawns(boolean[][] walls, int theme, Random random, int activeRows) {
        int centerY = activeRows / 2;
        int[][] desired;
        if (theme == 0) {
            desired = new int[][]{{10, centerY - 3}, {14, centerY - 3},
                    {10, centerY + 3}, {14, centerY + 3}};
        } else if (theme == 5) {
            // 避开中央三格大门，机器人从装配舱四角依次释放。
            desired = new int[][]{{10, centerY - 1}, {14, centerY - 1},
                    {10, centerY + 1}, {14, centerY + 1}};
        } else {
            desired = new int[][]{{11, centerY}, {13, centerY},
                    {12, centerY - 1}, {12, centerY + 1}};
        }
        int[][] out = new int[4][2];
        List<int[]> used = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            int[] p = nearestFloor(walls, desired[i][0], desired[i][1], used);
            out[i] = p;
            used.add(p);
        }
        return out;
    }

    static int[] nearestFloor(boolean[][] walls, int tx, int ty, List<int[]> forbidden) {
        int bestX = 1, bestY = 1, best = Integer.MAX_VALUE;
        for (int y = 1; y < ROWS - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (walls[y][x]) continue;
                boolean blocked = false;
                if (forbidden != null) {
                    for (int[] p : forbidden) {
                        if (Math.abs(p[0] - x) + Math.abs(p[1] - y) < 2) {
                            blocked = true;
                            break;
                        }
                    }
                }
                if (blocked) continue;
                int d = Math.abs(tx - x) + Math.abs(ty - y);
                if (d < best) {
                    best = d;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

    private static long signature(boolean[][] walls, int activeRows) {
        long h = 0xcbf29ce484222325L ^ activeRows;
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                h ^= walls[y][x] ? 1L : 0L;
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    private static void carveFallback(boolean[][] walls) {
        for (int y = 1; y < ROWS - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if ((x & 1) == 1 || (y & 1) == 1) walls[y][x] = false;
            }
        }
        carveSpawnSafety(walls);
    }
}
MAZE_GENERATOR

cp "$script_dir/GameView.java" generated-game/app/src/main/java/com/qi/xingdoumaze/GameView.java






echo "工程生成完成：$(find generated-game/app/src -type f | wc -l) 个源码/资源文件"

mkdir -p generated-game/app/src/test/java/com/qi/xingdoumaze
cat > generated-game/app/src/test/java/com/qi/xingdoumaze/GameRegressionTest.java <<'GAME_REGRESSION'
package com.qi.xingdoumaze;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Tests exercise GameView itself; there is no second implementation of its AI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GameRegressionTest {
    private GameView view;
    private Context context;
    private static final Class<?> ACTOR = nested("Actor");
    private static Class<?> nested(String name) {
        try { return Class.forName(GameView.class.getName() + "$" + name); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static Object value(String type, String name) {
        return Enum.valueOf((Class) nested(type), name);
    }
    private static Object get(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            try { return m.invoke(target, args); }
            catch (InvocationTargetException e) { throw new AssertionError(name, e.getCause()); }
        }
        throw new NoSuchMethodException(name);
    }
    private static Object actor(int x, int y, int kind) throws Exception {
        Constructor<?> c = ACTOR.getDeclaredConstructor(int.class, int.class, int.class);
        c.setAccessible(true); return c.newInstance(x, y, kind);
    }
    private void start(String theme) throws Exception { call(view, "startGame", value("Theme", theme), 20261006L); }
    private void openFixture() throws Exception {
        start("CLASSIC");
        boolean[][] walls = (boolean[][]) get(view,"walls");
        for (int y=0;y<29;y++) for(int x=0;x<25;x++) walls[y][x]=x==0||x==24||y==0||y==28;
        Arrays.fill((Object[])get(view,"enemies"), null);
        for (boolean[] row : (boolean[][])get(view,"pellets")) Arrays.fill(row,false);
        for (boolean[] row : (boolean[][])get(view,"powerPellets")) Arrays.fill(row,false);
        set(view,"player",actor(3,3,-1)); set(view,"companion",actor(8,8,10));
        set(view,"pelletsRemaining",1); ((boolean[][])get(view,"pellets"))[20][20]=true;
        set(view,"safeUntil",0f); set(view,"gameTime",0f); set(view,"autoMoveMode",false);
        set(view,"playerJoystickActive",true);
    }
    @Before public void setup() {
        context=ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("星豆迷宫记录",Context.MODE_PRIVATE).edit().clear().commit();
        view=new GameView(context); view.layout(0,0,1080,2160);
    }
    @After public void teardown() throws Exception { call(view,"onDetachedFromWindow"); }

    @Test public void advancedCompanionUsesNormalSummonAndOnlyOneWeapon() throws Exception {
        start("CLOWN"); Object ai=get(view,"companion");
        assertFalse((boolean)get(ai,"alive"));
        call(view,"activateCompanion","test");
        assertTrue((boolean)get(ai,"alive")); assertEquals(10,get(ai,"kind"));
        assertTrue((boolean)get(view,"companionBombAvailable"));
        set(view,"companionBombAvailable",false);
        call(view,"activateCompanion","again");
        assertFalse((boolean)get(view,"companionBombAvailable"));
        assertFalse((boolean)get(view,"companionOnly"));
    }

    @Test public void joystickHasSmallDeadzoneAndStableDiagonals() throws Exception {
        openFixture(); call(view,"resetJoystick");
        float cx=(float)get(view,"joystickCx"), cy=(float)get(view,"joystickCy"), r=(float)get(view,"joystickRadius");
        call(view,"updateJoystickInput",cx+r*.14f,cy); assertTrue((boolean)get(view,"playerJoystickActive"));
        call(view,"updateJoystickInput",cx+r*.09f,cy); assertTrue((boolean)get(view,"playerJoystickActive"));
        call(view,"updateJoystickInput",cx+r*.07f,cy); assertFalse((boolean)get(view,"playerJoystickActive"));
        call(view,"updateJoystickInput",cx+r*.6f,cy+r*.58f); assertEquals(value("Dir","RIGHT"),get(get(view,"player"),"wanted"));
        call(view,"updateJoystickInput",cx+r*.58f,cy+r*.6f); assertEquals(value("Dir","RIGHT"),get(get(view,"player"),"wanted"));
        call(view,"updateJoystickInput",cx+r*.45f,cy+r*.7f); assertEquals(value("Dir","DOWN"),get(get(view,"player"),"wanted"));
        call(view,"updateJoystickInput",cx,cy-r*.8f); assertEquals(value("Dir","UP"),get(get(view,"player"),"wanted"));
    }

    private MotionEvent touch(int action, int[] ids, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[ids.length];
        for(int i=0;i<ids.length;i++) {
            props[i]=new MotionEvent.PointerProperties();props[i].id=ids[i];props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=xs[i];coords[i].y=ys[i];coords[i].pressure=1f;coords[i].size=1f;
        }
        long now=SystemClock.uptimeMillis();
        return MotionEvent.obtain(now,now,action,ids.length,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
    }
    private void send(int action,int[] ids,float[] xs,float[] ys) {
        MotionEvent e=touch(action,ids,xs,ys);view.onTouchEvent(e);e.recycle();
    }
    @Test public void secondFingerCannotStealJoystickAndCancelClearsIt() throws Exception {
        openFixture();
        float cx=(float)get(view,"joystickCx"), cy=(float)get(view,"joystickCy");
        send(MotionEvent.ACTION_DOWN,new int[]{7},new float[]{cx+50},new float[]{cy});
        assertEquals(7,get(view,"joystickPointer"));
        send(MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{7,9},new float[]{cx+50,cx-50},new float[]{cy,cy});
        assertEquals(7,get(view,"joystickPointer"));
        send(MotionEvent.ACTION_POINTER_UP|(1<<8),new int[]{7,9},new float[]{cx+50,cx-50},new float[]{cy,cy});
        assertTrue((boolean)get(view,"playerJoystickActive"));
        send(MotionEvent.ACTION_CANCEL,new int[]{7},new float[]{cx+50},new float[]{cy});
        assertEquals(-1,get(view,"joystickPointer"));assertFalse((boolean)get(view,"playerJoystickActive"));
    }

    @Test public void lateTurnWindowDoesNotCutThroughWalls() throws Exception {
        openFixture(); Object p=get(view,"player");
        set(p,"dir",value("Dir","RIGHT"));set(p,"wanted",value("Dir","UP"));set(p,"progress",.08f);
        call(view,"moveActor",p,.01f,5f,false);
        assertEquals(value("Dir","UP"),get(p,"dir"));
        call(p,"place",3,3);set(p,"dir",value("Dir","RIGHT"));set(p,"wanted",value("Dir","UP"));set(p,"progress",.08f);
        ((boolean[][])get(view,"walls"))[2][3]=true;
        call(view,"moveActor",p,.01f,5f,false);assertEquals(value("Dir","RIGHT"),get(p,"dir"));
    }

    @Test public void reversalIsContinuousAndPauseClearsManualInput() throws Exception {
        openFixture();Object p=get(view,"player");
        set(p,"dir",value("Dir","RIGHT"));set(p,"wanted",value("Dir","LEFT"));set(p,"progress",.4f);
        float before=(float)call(p,"x");call(view,"moveActor",p,.01f,5f,false);
        assertEquals(before-.05f,(float)call(p,"x"),.0001f);
        set(view,"joystickPointer",7);view.pauseFromSystem();
        assertEquals(-1,get(view,"joystickPointer"));assertFalse((boolean)get(view,"playerJoystickActive"));
        view.resumeFromSystem(); assertEquals(value("Screen","PAUSED"),get(view,"screen"));
    }

    @Test public void exitTakesPriorityOverPowerChase() throws Exception {
        openFixture();Object ai=get(view,"companion");call(ai,"place",0,13);
        boolean[][] w=(boolean[][])get(view,"walls");w[13][0]=w[13][24]=false;
        set(view,"escapeActive",true);set(view,"escapeUnlocked",true);set(view,"escapeX",0);set(view,"escapeY",13);
        set(view,"powerUntil",10f);((Object[])get(view,"enemies"))[0]=actor(1,13,0);
        assertEquals(value("Dir","LEFT"),call(view,"chooseExpertDirection",ai));
        call(view,"moveActor",ai,.25f,5f,true);assertEquals(value("Screen","WIN"),get(view,"screen"));
    }

    @Test public void factoryRushDoesNotDisableEnemyRisk() throws Exception {
        openFixture();set(view,"theme",value("Theme","FACTORY"));
        set(view,"factoryCountdownStarted",true);set(view,"factoryDeadline",100f);
        Object ai=get(view,"companion"); call(ai,"place",8,8);
        ((Object[])get(view,"enemies"))[0]=actor(9,8,0);
        ((boolean[][])get(view,"pellets"))[8][9]=true;
        Object direction=call(view,"chooseExpertDirection",ai);
        assertNotEquals(value("Dir","RIGHT"),direction);
        set(view,"powerUntil",.03f);
        direction=call(view,"chooseExpertDirection",ai);
        assertNotEquals(value("Dir","RIGHT"),direction);
    }

    @Test public void beamStorageStaysBoundedAndDoesNotRepeatFoodRewards() throws Exception {
        openFixture(); Object ai=get(view,"companion");
        Set<Object> states=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object buffer:(Object[])get(view,"rolloutPool"))Collections.addAll(states,(Object[])buffer);
        assertEquals(256,states.size());
        for(int i=0;i<160;i++) {
            set(view,"gameTime",i*.2f);
            Object d=call(view,"chooseExpertDirection",ai);
            assertTrue((boolean)call(view,"canMove",(int)get(ai,"cellX"),(int)get(ai,"cellY"),d));
        }
        for(Object buffer:(Object[])get(view,"rolloutPool"))for(Object state:(Object[])buffer)assertTrue(states.contains(state));
    }

    @Test public void survivorUsesWormholeAndHeartOnItself() throws Exception {
        openFixture();Object ai=get(view,"companion"),p=get(view,"player");set(p,"alive",false);set(view,"companionOnly",true);
        Class<?> item=nested("Item");Constructor<?> ctor=item.getDeclaredConstructors()[0];ctor.setAccessible(true);
        List items=(List)get(view,"items");items.add(ctor.newInstance(value("ItemType","WORMHOLE"),8,8,100f));
        call(view,"collectItemAt",ai);
        assertTrue((int)get(ai,"cellX")!=8||(int)get(ai,"cellY")!=8);assertEquals(3,get(p,"cellX"));
        assertTrue((float)get(view,"companionProtectedUntil")>0);
        set(view,"companionProtectedUntil",0f);set(view,"heartUntil",10f);
        call(view,"damageCompanion","test");assertFalse((boolean)get(ai,"alive"));
        assertTrue((float)get(view,"companionReviveAt")>0f);
        set(view,"gameTime",2f);call(view,"updateCompanionRevival");assertTrue((boolean)get(ai,"alive"));
    }

    @Test public void survivorReturnsToAirBeforeDrowning() throws Exception {
        openFixture();set(view,"theme",value("Theme","OCEAN"));set(view,"companionOnly",true);
        set(view,"tideLevel",.6f);set(view,"breath",.9f);set(get(view,"player"),"alive",false);
        Object ai=get(view,"companion");call(ai,"place",8,14);
        assertEquals(value("Dir","UP"),call(view,"chooseExpertDirection",ai));
        call(ai,"place",8,10);set(view,"breath",1.5f);
        assertEquals(value("Dir","NONE"),call(view,"chooseExpertDirection",ai));
        assertTrue((boolean)get(view,"expertWantsWait"));
        set(view,"breath",5f);call(view,"chooseExpertDirection",ai);assertFalse((boolean)get(view,"expertWantsWait"));
    }

    @Test public void mapCacheErasesEatenPelletAndReusesItsBitmap() throws Exception {
        openFixture();Object p=get(view,"player");
        ((boolean[][])get(view,"pellets"))[3][3]=true;set(view,"wallCacheDirty",true);call(view,"ensureWallCache");
        Bitmap cache=(Bitmap)get(view,"wallCache");float scale=(float)get(view,"wallCacheScale");
        int x=Math.round((float)call(view,"gx",3)*scale),y=Math.round((float)call(view,"gy",3)*scale);
        assertTrue((cache.getPixel(x,y)>>>24)>0);call(view,"collectPellet",p);assertEquals(0,cache.getPixel(x,y)>>>24);
        set(view,"wallCacheDirty",true);call(view,"ensureWallCache");assertSame(cache,get(view,"wallCache"));
    }

    @Test public void allThemesRunTheActualGameLoopWithoutInvalidActorCells() throws Exception {
        String[] themes={"CLOWN","CLASSIC","OCEAN","SPRING","GALAXY","FACTORY"};
        for(String theme:themes)for(String difficulty:new String[]{"EASY","MEDIUM","HARD"}) {
            set(view,"difficulty",value("Difficulty",difficulty)); start(theme);call(view,"activateCompanion","test");
            set(get(view,"player"),"alive",false);set(view,"companionOnly",true);
            for(int tick=0;tick<3600 && get(view,"screen")==value("Screen","PLAYING");tick++) {
                call(view,"updateGame",1f/60f);
                Object ai=get(view,"companion");
                assertTrue((int)get(ai,"cellX")>=0&&(int)get(ai,"cellX")<25);
                assertTrue((int)get(ai,"cellY")>=0&&(int)get(ai,"cellY")<29);
            }
            assertNull(get(view,"guardedError"));
        }
    }

    @Test public void companionCanClearEveryThemeWithoutEnemiesOrEvents() throws Exception {
        for(String theme:new String[]{"CLOWN","CLASSIC","OCEAN","SPRING","GALAXY","FACTORY"}) {
            start(theme);Arrays.fill((Object[])get(view,"enemies"),null);
            call(view,"activateCompanion","test");Object ai=get(view,"companion");
            set(get(view,"player"),"alive",false);set(ai,"stunnedUntil",0f);
            for(int tick=0;tick<6000 && (int)get(view,"pelletsRemaining")>0;tick++) {
                set(view,"gameTime",tick*.2f);call(view,"moveActor",ai,.2f,5f,true);
            }
            assertEquals(theme+" has stranded pellets",0,get(view,"pelletsRemaining"));
        }
    }

    @Test public void generatedMapsStayConnectedIncludingClosedFactoryDoor() {
        int[] dx={-1,1,0,0},dy={0,0,-1,1};
        for(int theme=0;theme<6;theme++)for(int seed=0;seed<200;seed++) {
            MazeGenerator.Result r=MazeGenerator.generate(theme,20261006L+seed*104729L);
            boolean[][] w=r.walls;
            if(theme==5)for(int x=11;x<=13;x++)w[14][x]=true;
            boolean[][] seen=new boolean[29][25];int[] q=new int[725];int head=0,tail=0;
            q[tail++]=r.playerY*25+r.playerX;seen[r.playerY][r.playerX]=true;
            while(head<tail){int id=q[head++],x=id%25,y=id/25;
                for(int d=0;d<4;d++){int nx=x+dx[d],ny=y+dy[d];
                    if(nx<0||nx>=25){if(r.tunnelAxis==1&&y==r.tunnelCoordinate)nx=(nx+25)%25;else continue;}
                    if(ny<0||ny>=29){if(r.tunnelAxis==2&&x==r.tunnelCoordinate)ny=(ny+29)%29;else continue;}
                    if(!w[ny][nx]&&!seen[ny][nx]){seen[ny][nx]=true;q[tail++]=ny*25+nx;}
                }
            }
            for(int y=0;y<29;y++)for(int x=0;x<25;x++)assertTrue("theme="+theme+" seed="+seed+" cell="+x+","+y,w[y][x]||seen[y][x]);
        }
    }

    @Test public void screensRenderAtPhoneAspectRatios() throws Exception {
        String out=System.getenv("GAME_PREVIEW_DIR");
        int[][] sizes={{720,1280},{1080,2400},{1440,3200}};
        for(int[] size:sizes){
            view.layout(0,0,size[0],size[1]);set(view,"screen",value("Screen","HOME"));
            render("home-"+size[0],size[0],size[1],out);
        }
        view.layout(0,0,1080,2160);set(view,"screen",value("Screen","MAP_SELECT"));render("maps",1080,2160,out);
        for(String theme:new String[]{"CLOWN","CLASSIC","OCEAN","SPRING","GALAXY","FACTORY"}) {
            start(theme);render(theme.toLowerCase(),1080,2160,out);
        }
    }
    private void render(String name,int width,int height,String output) throws Exception {
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));
        assertNull(name,get(view,"guardedError"));
        if(output!=null){File f=new File(output,name+".png");f.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.PNG,100,stream);}}
        b.recycle();
    }
}

GAME_REGRESSION
