package com.qi.xingdoumaze;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Comparator;

/**
 * 星豆迷宫：原生 Canvas 游戏，静态地图缓存与动态角色分层绘制。
 */
public final class GameView extends View {
    private static final int COLS = MazeGenerator.COLS;
    private static final int ROWS = MazeGenerator.ROWS;
    private static final float CONTROL_TOP_RATIO = 0.70f;
    private static final float PLAYER_SPEED = 5.15f;
    private static final float BASE_ENEMY_SPEED = 3.78f;
    private static final float COMPANION_SPEED = 5.00f;
    private static final int FACTORY_DOOR_X = COLS / 2;
    private static final int FACTORY_DOOR_Y = ROWS / 2;
    private static final float EPS = 0.0001f;
    private static final Dir[] CARDINALS = {Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT};
    private static final int[] DOT_COLORS = {0xFFFFD25B, 0xFFFF7EB7, 0xFF61DFFF, 0xFF73E28F};
    private static final Typeface FONT_NORMAL = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface FONT_BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface FONT_TITLE = Typeface.create("sans-serif-black", Typeface.BOLD);

    private enum Screen { HOME, MAP_SELECT, GUIDE, PLAYING, PAUSED, GAME_OVER, WIN }

    private enum Difficulty {
        EASY("简单", "随机减少一名敌人"),
        MEDIUM("中等", "保留四名策略敌人"),
        HARD("困难", "额外增加随机游走敌人");

        final String label;
        final String description;

        Difficulty(String label, String description) {
            this.label = label;
            this.description = description;
        }

        static Difficulty fromOrdinal(int value) {
            Difficulty[] values = values();
            return values[Math.max(0, Math.min(values.length - 1, value))];
        }
    }

    private enum Theme {
        CLOWN("小丑乐园", "集彩球 · 召唤小跟班 · 彩幕门", 0xFF7D2B8D, 0xFFFFC857),
        CLASSIC("经典迷宫", "清空豆子 · 侧边隧道逃生", 0xFF15356A, 0xFF4B91FF),
        OCEAN("海洋世界", "贝壳 · 发光珍珠 · 救生舱", 0xFF12627D, 0xFF48E1EE),
        SPRING("新春盛景", "爆竹豆 · 年门", 0xFF962335, 0xFFFFCE5C),
        GALAXY("银河系", "集齐星球 · 脚下虫洞逃生", 0xFF35206C, 0xFFA98AFF),
        FACTORY("机械工厂", "齿轮豆 · 传送带 · 制造伙伴 · 中央门", 0xFF31556A, 0xFFFFB74D);

        final String title;
        final String subtitle;
        final int dark;
        final int accent;

        Theme(String title, String subtitle, int dark, int accent) {
            this.title = title;
            this.subtitle = subtitle;
            this.dark = dark;
            this.accent = accent;
        }
    }

    private enum Dir {
        NONE(0, 0), UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);
        final int dx;
        final int dy;

        Dir(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        Dir opposite() {
            switch (this) {
                case UP: return DOWN;
                case DOWN: return UP;
                case LEFT: return RIGHT;
                case RIGHT: return LEFT;
                default: return NONE;
            }
        }
    }

    private enum ItemType { GIFT, HEART, BIG_CRACKER, WORMHOLE, GATE_CRACKER, FAIRY_WAND }

    private static final class Actor {
        int cellX;
        int cellY;
        int spawnX;
        int spawnY;
        int kind;
        Dir dir = Dir.NONE;
        Dir wanted = Dir.NONE;
        float progress;
        float stunnedUntil;
        boolean alive = true;
        boolean revealed;
        boolean forced;
        float forcedX;
        float forcedY;
        float pullStartX;
        float pullStartY;
        float releaseAt;

        Actor(int x, int y, int kind) {
            cellX = spawnX = x;
            cellY = spawnY = y;
            this.kind = kind;
        }

        float x() { return forced ? forcedX : cellX + dir.dx * progress; }
        float y() { return forced ? forcedY : cellY + dir.dy * progress; }

        void place(int x, int y) {
            cellX = x;
            cellY = y;
            progress = 0f;
            dir = Dir.NONE;
            wanted = Dir.NONE;
            forced = false;
        }
    }

    private static final class ExpertRolloutState {
        int x, y, steps;
        Dir dir, first;
        float score, minMargin, arrival, powerEnd;
        final int[] route = new int[10];
        float rank() { return score + Math.min(4f, minMargin) * 13f; }
    }

    private static final Comparator<ExpertRolloutState> ROLLOUT_ORDER =
            (a, b) -> Float.compare(b.rank(), a.rank());

    private static final class Item {
        final ItemType type;
        final int x;
        final int y;
        final float expires;

        Item(ItemType type, int x, int y, float expires) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.expires = expires;
        }
    }

    private static final class Bomb {
        final int x;
        final int y;
        final float explodeAt;
        final float radius;
        final boolean big;
        boolean exploded;
        float vanishAt;

        Bomb(int x, int y, float explodeAt, float radius, boolean big) {
            this.x = x;
            this.y = y;
            this.explodeAt = explodeAt;
            this.radius = radius;
            this.big = big;
        }
    }

    private static final class Meteor {
        int x;
        int y;
        float impactAt;
        float vanishAt;
        boolean active;
        boolean impacted;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmpRect = new RectF();
    private final RectF startButton = new RectF();
    private final RectF guideButton = new RectF();
    private final RectF backButton = new RectF();
    private final RectF lifeMinusButton = new RectF();
    private final RectF lifePlusButton = new RectF();
    private final RectF difficultyButton = new RectF();
    private final RectF[] playerStyleButtons = new RectF[2];
    private final RectF pauseButton = new RectF();
    private final RectF toggleButton = new RectF();
    private final RectF bombButton = new RectF();
    private final RectF resumeButton = new RectF();
    private final RectF exitButton = new RectF();
    private final RectF[] mapCards = new RectF[Theme.values().length];
    private final Random random = new Random();
    private final SharedPreferences prefs;
    // 延迟创建音频对象，规避部分设备在应用启动阶段分配音轨失败而直接闪退。
    private ToneGenerator tone;
    private boolean audioUnavailable;
    private final Drawable appAvatar;
    private final int[][] bfsDistance = new int[ROWS][COLS];
    private final int[] queueX = new int[COLS * ROWS];
    private final int[] queueY = new int[COLS * ROWS];
    private final int[][] pelletDistance = new int[ROWS][COLS];
    private final float[] starX = new float[56];
    private final float[] starY = new float[56];
    private final float[] starSize = new float[56];

    private Screen screen = Screen.HOME;
    private Screen screenBeforeGuide = Screen.HOME;
    private Theme theme = Theme.CLOWN;
    private boolean systemPaused;
    private boolean autoMoveMode = true;
    private boolean playerJoystickActive;
    private Difficulty difficulty = Difficulty.MEDIUM;
    private int configuredLives = 1;
    private int playerStyle;
    private int mazeRows = ROWS;
    private float mazeVisualScale = 1f;
    private int tunnelAxis = MazeGenerator.TUNNEL_NONE;
    private int tunnelCoordinate = -1;
    private boolean[][] walls = new boolean[ROWS][COLS];
    private boolean[][] pellets = new boolean[ROWS][COLS];
    private boolean[][] powerPellets = new boolean[ROWS][COLS];
    private float[][] brokenUntil = new float[ROWS][COLS];
    private final byte[][] factoryConveyors = new byte[ROWS][COLS]; // 0无，1横向，2纵向
    private Actor player;
    private Actor companion;
    private final Actor[] enemies = new Actor[5];
    private final List<Item> items = new ArrayList<>();
    private final List<Bomb> activeBombs = new ArrayList<>();
    private final Meteor meteor = new Meteor();

    private int viewW;
    private int viewH;
    private float controlTop;
    private float mazeTop;
    private float mazeBottom;
    private float cell;
    private float originX;
    private float originY;
    private float joystickCx;
    private float joystickCy;
    private float joystickRadius;
    private float joystickKnobX;
    private float joystickKnobY;
    private int joystickPointer = -1;
    private Bitmap wallCache;
    private boolean wallCacheDirty = true;
    private Shader backgroundShader;

    private long lastFrameNanos;
    private float gameTime;
    private int score;
    private int bestScore;
    private int lives;
    private int bombCount;
    private int pelletsRemaining;
    private int pelletsEaten;
    private int enemyEatChain;
    private int clownBallsCollected;
    private boolean companionSummoned;
    private final int[][] clownBallColors = new int[ROWS][COLS];
    private float safeUntil;
    private float invincibleUntil;
    private float shieldUntil;
    private float speedUntil;
    private float powerUntil;
    private float heartUntil;
    private float reviveAt = -1f;
    private boolean heartRevive;
    private boolean companionOnly;
    private boolean companionBombAvailable;
    private float companionProtectedUntil;

    // 高级AI规划器：持久目标、风险路径、反复徘徊检测与紧急脱困。
    private final float[][] expertEnemyEta = new float[ROWS][COLS];
    private final float[][] expertPathCost = new float[ROWS][COLS];
    private final float[][] expertPathArrival = new float[ROWS][COLS];
    private final float[][] expertHazards = new float[ROWS][COLS];
    private final int[][] expertExits = new int[ROWS][COLS];
    private final int[][] expertPlayerDistance = new int[ROWS][COLS];
    private final int[][] expertDryDistance = new int[ROWS][COLS];
    private final int[][] pelletPrefix = new int[ROWS + 1][COLS + 1];
    private final int[] expertHeap = new int[ROWS * COLS];
    private final int[] expertHeapPosition = new int[ROWS * COLS];
    private final Dir[] expertRouteFirst = new Dir[ROWS * COLS];
    private final ExpertRolloutState[][] rolloutPool = new ExpertRolloutState[2][128];
    private int expertHeapSize;
    private float expertEmergencyAt;
    private float expertLastReverseAt = -1f;
    private Dir joystickDirection = Dir.NONE;
    private final Paint clearPaint = new Paint();
    private final Paint cachePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Canvas wallCacheCanvas;
    private float wallCacheScale = 1f;
    private Shader menuShader, controlsShader, galaxyShader, heroShader;
    private int brokenWallCount;
    private int breathLabelTick = -1;
    private boolean breathLabelBubble;
    private String breathLabel = "";
    private final int[][] expertVisitHeat = new int[ROWS][COLS];
    private boolean expertRecoveringBreath;
    private boolean expertWantsWait;
    private int expertTargetX = -1;
    private int expertTargetY = -1;
    private float expertTargetUntil;
    private int expertLastPelletsRemaining = -1;
    private float expertLastProgressAt;
    private String expertPlanLabel = "规划中";

    private float companionReviveAt = -1f;
    private String banner = "";
    private float bannerUntil;
    private String guardedError;

    // 主题任务与逃生
    private boolean escapeActive;
    private boolean escapeUnlocked;
    private int escapeX;
    private int escapeY;
    private float escapeSpawnAt;
    private float galaxyEscapeAt = -1f;
    private boolean gateCrackerArmed;
    private int gateCrackerX;
    private int gateCrackerY;
    private float gateCrackerExplodeAt;

    // 小丑乐园事件
    private float nextGiftAt;
    private float nextClownBuffAt;
    private float clownWaveStart = -1f;
    private float enemyBoostStart;
    private float enemyBoostUntil;
    private float enemyInvisibleStart;
    private float enemyInvisibleUntil;
    private boolean wasInClownRange;
    private float clownAttackStart = -10f;
    private Dir clownAttackDir = Dir.NONE;
    private boolean clownAttackHit;

    // 海洋事件：潮位总体逐步抬高，偶尔会短暂退潮；退潮不降低下一次涨潮的最高阶段。
    private int tideStage;
    private boolean tideRising = true;
    private float tideLevel;
    private float tideTarget;
    private float nextTideAt;
    private boolean tideWarned;
    private float breath = 5f;
    private float bubbleUntil;
    private boolean oceanPearlBoost;
    private boolean oceanPearlPresent;
    private int oceanPearlX = -1;
    private int oceanPearlY = -1;

    // 其他主题事件
    private float nextHeartAt;
    private float nextBigCrackerAt;

    // 新春游龙：平时在地图下方作为背景游动，低频实体化冲向玩家。
    private float nextDragonDashAt;
    private boolean dragonDashing;
    private boolean dragonCorrected;
    private boolean dragonHitApplied;
    private float dragonWarningStart;
    private float dragonMoveStart;
    private float dragonSegmentStartAt;
    private float dragonDashEnd;
    private float dragonStartX;
    private float dragonStartY;
    private float dragonTargetX;
    private float dragonTargetY;
    private float dragonCurrentX;
    private float dragonCurrentY;
    private float dragonAimX;
    private float dragonAimY;
    private float nextBlackHoleAt;
    private float nextWormholeAt;
    private float nextMeteorAt;
    private boolean blackHoleRunning;
    private boolean blackHolePullStarted;
    private int blackHoleMode; // 0=玩家，1=全部敌人
    private float blackHoleStart;
    private float blackHoleActiveAt;
    private float blackHoleEnd;
    private float globalScatterUntil;

    // 机械工厂与经典困难事件
    private int factoryInitialPellets;
    private boolean factoryCountdownStarted;
    private float factoryDeadline;
    private int lastFactorySecond = -1;
    private float nextClassicShiftAt;
    private String failureReason;

    // 机械工厂：制造点、返厂充电与机械臂
    private int factoryMakerX = -1;
    private int factoryMakerY = -1;
    private float factoryMakerProgress;
    private float nextFactoryMakerRefreshAt;
    private int factoryBaseX = FACTORY_DOOR_X;
    private int factoryBaseY = FACTORY_DOOR_Y + 1;
    private final int[] factoryChargeTargetX = new int[5];
    private final int[] factoryChargeTargetY = new int[5];
    private final float[] factoryReturnSpeed = new float[5];
    private final boolean[] factoryAtBase = new boolean[5];
    private final boolean[] factoryChargeCompleted = new boolean[5];
    private final float[] factoryChargeUntilByEnemy = new float[5];
    private boolean factoryReturningToCharge;
    private boolean factoryCharging;
    private float factoryNextChargeAt;
    private float factoryReturnDeadline;
    private float factoryChargeUntil;
    private float nextFactoryArmAt;
    private boolean factoryArmPullActive;
    private float factoryArmPullStartAt;
    private float factoryArmPullEndAt;
    private float factoryArmStartX;
    private float factoryArmStartY;
    private int factoryArmTargetX;
    private int factoryArmTargetY;
    private int factoryArmEnemyKind = -1;

    // 机械工厂：随机激光炮台。蓄力时持续跟踪，最后短暂锁定后发射粗激光。
    private int factoryTurretX = -1;
    private int factoryTurretY = -1;
    private float factoryTurretNextAt;
    private boolean factoryTurretCharging;
    private boolean factoryTurretLocked;
    private boolean factoryTurretHitApplied;
    private float factoryTurretChargeStart;
    private float factoryTurretFireAt;
    private float factoryTurretFireUntil;
    private float factoryTurretAimX;
    private float factoryTurretAimY;
    private final float[] factoryTurretBeamEnd = new float[2];

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        prefs = context.getSharedPreferences("星豆迷宫记录", Context.MODE_PRIVATE);
        appAvatar = loadAvatar(context);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        for (ExpertRolloutState[] buffer : rolloutPool) {
            for (int i = 0; i < buffer.length; i++) buffer[i] = new ExpertRolloutState();
        }
        autoMoveMode = prefs.getBoolean("自动移动模式", true);
        configuredLives = clamp(prefs.getInt("生命设置", 1), 1, 5);
        difficulty = Difficulty.fromOrdinal(prefs.getInt("难度设置", Difficulty.MEDIUM.ordinal()));
        playerStyle = clamp(prefs.getInt("玩家形象", 0), 0, 1);
        textPaint.setTypeface(FONT_NORMAL);
        strokePaint.setStyle(Paint.Style.STROKE);
        Random stars = new Random(20260712L);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = stars.nextFloat();
            starY[i] = stars.nextFloat();
            starSize[i] = 0.5f + stars.nextFloat() * 1.8f;
        }
        for (int i = 0; i < mapCards.length; i++) mapCards[i] = new RectF();
        for (int i = 0; i < playerStyleButtons.length; i++) playerStyleButtons[i] = new RectF();
    }

    private Drawable loadAvatar(Context context) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(context.getResources(), R.drawable.app_avatar, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return context.getDrawable(R.drawable.app_avatar);
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.app_avatar, options);
        return bitmap == null ? context.getDrawable(R.drawable.app_avatar)
                : new BitmapDrawable(context.getResources(), bitmap);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        viewW = w;
        viewH = h;
        controlTop = h * CONTROL_TOP_RATIO;
        updateMazeGeometry();
        joystickCx = w * 0.27f;
        joystickCy = controlTop + (h - controlTop) * 0.57f;
        joystickRadius = Math.min(w * 0.185f, (h - controlTop) * 0.34f);
        joystickKnobX = joystickCx;
        joystickKnobY = joystickCy;
        updateUiRects();
        rebuildBackgroundShader();
        resetJoystick();
        menuShader = new LinearGradient(0, 0, w, h,
                new int[]{0xFF080F26, 0xFF172646, 0xFF113C46}, null, Shader.TileMode.CLAMP);
        controlsShader = new LinearGradient(0, controlTop, 0, h, 0xFF171D34, 0xFF080D1D, Shader.TileMode.CLAMP);
        heroShader = new RadialGradient(w * 0.5f, h * 0.295f, Math.max(1f, w * 0.48f),
                0x455EE5CD, 0x00162A47, Shader.TileMode.CLAMP);
        wallCacheDirty = true;
    }

    private void updateMazeGeometry() {
        if (viewW <= 0 || viewH <= 0) return;
        float hudHeight = viewH * 0.052f;
        float available = controlTop - hudHeight;
        // 所有地图始终保留完整25×29格，只在两个接近的等比例尺寸间切换。
        float baseCell = Math.min(viewW * 0.955f / COLS, available * 0.970f / ROWS);
        cell = baseCell * mazeVisualScale;
        originX = (viewW - cell * COLS) * 0.5f;
        originY = hudHeight + (available - cell * ROWS) * 0.5f;
        mazeTop = originY;
        mazeBottom = originY + cell * ROWS;
        galaxyShader = new LinearGradient(0, mazeTop, viewW, mazeBottom,
                0x00387DFF, 0x333BD6E6, Shader.TileMode.CLAMP);
    }

    private void updateUiRects() {
        float bw = viewW * 0.58f;
        float bh = Math.max(52f, viewH * 0.061f);
        startButton.set((viewW - bw) / 2f, viewH * 0.60f,
                (viewW + bw) / 2f, viewH * 0.60f + bh);
        guideButton.set((viewW - bw) / 2f, viewH * 0.69f,
                (viewW + bw) / 2f, viewH * 0.69f + bh * 0.88f);
        backButton.set(viewW * 0.04f, viewH * 0.035f, viewW * 0.22f, viewH * 0.095f);
        pauseButton.set(viewW * 0.89f, viewH * 0.008f, viewW * 0.985f, viewH * 0.052f);
        toggleButton.set(viewW * 0.42f, controlTop + (viewH - controlTop) * 0.06f,
                viewW * 0.64f, controlTop + (viewH - controlTop) * 0.25f);
        float br = Math.min(viewW * 0.14f, (viewH - controlTop) * 0.25f);
        bombButton.set(viewW * 0.78f - br, joystickCy - br,
                viewW * 0.78f + br, joystickCy + br);
        resumeButton.set(viewW * 0.22f, viewH * 0.48f, viewW * 0.78f, viewH * 0.56f);
        exitButton.set(viewW * 0.22f, viewH * 0.60f, viewW * 0.78f, viewH * 0.68f);

        // 主题页整体下移约10%，六张卡片仍保持两列三行。
        float cardW = viewW * 0.435f;
        float cardH = viewH * 0.128f;
        float left = viewW * 0.045f;
        float right = viewW * 0.955f - cardW;
        float y1 = viewH * 0.145f;
        float gapY = viewH * 0.153f;
        for (int row = 0; row < 3; row++) {
            mapCards[row * 2].set(left, y1 + gapY * row,
                    left + cardW, y1 + gapY * row + cardH);
            mapCards[row * 2 + 1].set(right, y1 + gapY * row,
                    right + cardW, y1 + gapY * row + cardH);
        }

        // 设置区整体下移但按钮显著缩小，避免压住页面视觉重心。
        float settingsTop = viewH * 0.615f;
        float controlTopY = settingsTop + viewH * 0.050f;
        float controlBottom = settingsTop + viewH * 0.105f;
        lifeMinusButton.set(viewW * 0.082f, controlTopY,
                viewW * 0.148f, controlBottom);
        lifePlusButton.set(viewW * 0.292f, controlTopY,
                viewW * 0.358f, controlBottom);
        difficultyButton.set(viewW * 0.435f, controlTopY,
                viewW * 0.905f, controlBottom);

        float styleTop = viewH * 0.772f;
        float styleBottom = viewH * 0.842f;
        float styleGap = viewW * 0.022f;
        float styleW = (viewW * 0.78f - styleGap) / 2f;
        float styleLeft = viewW * 0.11f;
        for (int i = 0; i < playerStyleButtons.length; i++) {
            float sx = styleLeft + i * (styleW + styleGap);
            playerStyleButtons[i].set(sx, styleTop, sx + styleW, styleBottom);
        }

    }

    private void rebuildBackgroundShader() {
        int top = mixColor(theme.dark, Color.WHITE, 0.10f);
        int bottom = mixColor(theme.dark, 0xFF102D4A, 0.36f);
        backgroundShader = new LinearGradient(0, 0, 0, Math.max(1, viewH),
                top, bottom, Shader.TileMode.CLAMP);
    }

    private void startGame(Theme selected) {
        startGame(selected, System.nanoTime());
    }

    private void startGame(Theme selected, long entropy) {
        theme = selected;
        rebuildBackgroundShader();
        long counter = prefs.getLong("迷宫序号", 0L) + 1L;
        long seed = entropy ^ (counter * 0x9E3779B97F4A7C15L) ^ selected.ordinal();
        random.setSeed(seed ^ 0xD1B54A32D192ED03L);
        long previous = prefs.getLong("上次迷宫_" + selected.ordinal(), Long.MIN_VALUE);
        MazeGenerator.Result result = MazeGenerator.generate(selected.ordinal(), seed);
        for (int retry = 0; retry < 8 && result.signature == previous; retry++) {
            result = MazeGenerator.generate(selected.ordinal(), seed + retry * 104729L);
        }
        prefs.edit().putLong("迷宫序号", counter)
                .putLong("上次迷宫_" + selected.ordinal(), result.signature).apply();

        walls = result.walls;
        mazeRows = result.activeRows;
        mazeVisualScale = result.visualScale;
        tunnelAxis = result.tunnelAxis;
        tunnelCoordinate = result.tunnelCoordinate;
        if (selected == Theme.FACTORY) {
            // 中央大门开局保持关闭。三格宽只影响门体区域，周围装配舱仍可绕行。
            for (int x = FACTORY_DOOR_X - 1; x <= FACTORY_DOOR_X + 1; x++) {
                walls[FACTORY_DOOR_Y][x] = true;
            }
        }
        updateMazeGeometry();
        pellets = new boolean[ROWS][COLS];
        powerPellets = new boolean[ROWS][COLS];
        brokenUntil = new float[ROWS][COLS];
        brokenWallCount = 0;
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) pellets[y][x] = !walls[y][x];
        }

        player = new Actor(result.playerX, result.playerY, -1);
        player.wanted = Dir.RIGHT;
        configureEnemies(result);
        companion = new Actor(result.companionX, result.companionY, 10);
        companion.alive = false;
        clearPelletsAround(player.cellX, player.cellY, 2);
        for (Actor enemy : enemies) {
            if (enemy != null) clearPelletsAround(enemy.cellX, enemy.cellY, 1);
        }
        placePowerPellets();
        factoryMakerX = factoryMakerY = -1;
        factoryMakerProgress = 0f;
        nextFactoryMakerRefreshAt = Float.MAX_VALUE;
        for (byte[] row : factoryConveyors) Arrays.fill(row, (byte) 0);
        if (selected == Theme.FACTORY) {
            int[] maker = chooseFactoryManufacturingPoint();
            factoryMakerX = maker[0];
            factoryMakerY = maker[1];
            pellets[factoryMakerY][factoryMakerX] = false;
            powerPellets[factoryMakerY][factoryMakerX] = false;
            configureFactoryConveyors();
            configureFactoryChargeTargets();
            configureFactoryTurret();
        }
        recountPellets();

        items.clear();
        activeBombs.clear();
        meteor.active = false;
        gameTime = 0f;
        score = 0;
        bestScore = prefs.getInt("最高分_" + theme.ordinal(), 0);
        lives = configuredLives;
        bombCount = 2;
        pelletsEaten = 0;
        enemyEatChain = 0;
        clownBallsCollected = 0;
        companionSummoned = false;
        safeUntil = 4f;
        invincibleUntil = shieldUntil = speedUntil = powerUntil = heartUntil = 0f;
        reviveAt = -1f;
        heartRevive = false;
        companionOnly = false;
        companionBombAvailable = false;
        companionProtectedUntil = 0f;
        companionReviveAt = -1f;
        escapeActive = false;
        escapeUnlocked = false;
        escapeX = escapeY = -1;
        escapeSpawnAt = 0f;
        galaxyEscapeAt = -1f;
        gateCrackerArmed = false;
        gateCrackerExplodeAt = 0f;
        guardedError = null;
        nextGiftAt = 20f + random.nextFloat() * 20f;
        nextClownBuffAt = 20f + random.nextFloat() * 20f;
        clownWaveStart = -1f;
        enemyBoostStart = enemyBoostUntil = 0f;
        enemyInvisibleStart = enemyInvisibleUntil = 0f;
        wasInClownRange = false;
        clownAttackStart = -10f;
        tideStage = 0;
        tideRising = true;
        tideLevel = tideTarget = 0f;
        nextTideAt = 18f;
        tideWarned = false;
        breath = 5f;
        bubbleUntil = 0f;
        oceanPearlBoost = false;
        nextHeartAt = 24f + random.nextFloat() * 16f;
        nextBigCrackerAt = 18f + random.nextFloat() * 15f;
        nextDragonDashAt = selected == Theme.SPRING ? 34f + random.nextFloat() * 26f : Float.MAX_VALUE;
        dragonDashing = false;
        dragonCorrected = false;
        dragonHitApplied = false;
        dragonWarningStart = dragonMoveStart = dragonSegmentStartAt = dragonDashEnd = 0f;
        nextBlackHoleAt = 10f + random.nextFloat() * 10f;
        nextWormholeAt = 18f + random.nextFloat() * 20f;
        nextMeteorAt = 12f + random.nextFloat() * 12f;
        blackHoleRunning = false;
        globalScatterUntil = 0f;
        factoryInitialPellets = pelletsRemaining;
        factoryCountdownStarted = false;
        factoryDeadline = 0f;
        lastFactorySecond = -1;
        nextClassicShiftAt = 65f + random.nextFloat() * 40f;
        factoryReturningToCharge = false;
        factoryCharging = false;
        Arrays.fill(factoryAtBase, false);
        Arrays.fill(factoryChargeCompleted, false);
        Arrays.fill(factoryChargeUntilByEnemy, 0f);
        factoryNextChargeAt = selected == Theme.FACTORY && difficulty == Difficulty.HARD ? 40f : Float.MAX_VALUE;
        factoryReturnDeadline = factoryChargeUntil = 0f;
        nextFactoryArmAt = 10f + random.nextFloat() * 7f;
        factoryArmPullActive = false;
        factoryArmEnemyKind = -1;
        // 制造点在每局开场随机选定一次，本局内保持固定。
        nextFactoryMakerRefreshAt = Float.MAX_VALUE;
        factoryTurretNextAt = selected == Theme.FACTORY ? 28f + random.nextFloat() * 14f : Float.MAX_VALUE;
        factoryTurretCharging = false;
        factoryTurretLocked = false;
        factoryTurretHitApplied = false;
        factoryTurretChargeStart = factoryTurretFireAt = factoryTurretFireUntil = 0f;
        failureReason = null;
        resetExpertPlanner();
        banner = theme.title + " · " + difficulty.label + " · 25×" + mazeRows;
        bannerUntil = 2.2f;
        screen = Screen.PLAYING;
        lastFrameNanos = 0L;
        joystickPointer = -1;
        joystickKnobX = joystickCx;
        joystickKnobY = joystickCy;
        playerJoystickActive = false;
        wallCacheDirty = true;
        playTone(ToneGenerator.TONE_PROP_ACK, 100);
        invalidate();
    }

    private void configureEnemies(MazeGenerator.Result result) {
        Arrays.fill(enemies, null);
        int omittedKind = difficulty == Difficulty.EASY ? random.nextInt(4) : -1;
        int releaseSlot = 0;
        for (int kind = 0; kind < 4; kind++) {
            if (kind == omittedKind) continue;
            Actor enemy = new Actor(result.enemySpawns[kind][0], result.enemySpawns[kind][1], kind);
            enemy.wanted = (kind % 2 == 0) ? Dir.LEFT : Dir.RIGHT;
            enemy.releaseAt = theme == Theme.FACTORY ? releaseSlot * 2f : 0f;
            enemies[kind] = enemy;
            releaseSlot++;
        }
        if (difficulty == Difficulty.HARD) {
            int[] spawn = theme == Theme.FACTORY
                    ? nearestPassable(COLS / 2, mazeRows / 2 + 2)
                    : chooseRandomEnemySpawn();
            Actor randomEnemy = new Actor(spawn[0], spawn[1], 4);
            randomEnemy.wanted = chooseAnyDirection(randomEnemy, Dir.NONE);
            randomEnemy.releaseAt = theme == Theme.FACTORY ? releaseSlot * 2f : 0f;
            enemies[4] = randomEnemy;
        }
    }

    private int[] chooseRandomEnemySpawn() {
        int bestX = COLS / 2;
        int bestY = mazeRows / 2;
        float bestValue = -Float.MAX_VALUE;
        for (int y = 2; y < mazeRows - 2; y++) {
            for (int x = 2; x < COLS - 2; x++) {
                if (isWall(x, y)) continue;
                float value = distance(x, y, player.cellX, player.cellY) * 2f + countExits(x, y);
                for (Actor enemy : enemies) {
                    if (enemy != null) value += Math.min(5f, distance(x, y, enemy.cellX, enemy.cellY));
                }
                value += random.nextFloat() * 4f;
                if (value > bestValue) {
                    bestValue = value;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

    private void clearPelletsAround(int cx, int cy, int radius) {
        for (int y = Math.max(0, cy - radius); y <= Math.min(mazeRows - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(COLS - 1, cx + radius); x++) {
                if (Math.abs(x - cx) + Math.abs(y - cy) <= radius) {
                    pellets[y][x] = false;
                    powerPellets[y][x] = false;
                }
            }
        }
    }

    private void placePowerPellets() {
        for (int[] row : clownBallColors) Arrays.fill(row, 0);
        oceanPearlPresent = false;
        oceanPearlX = oceanPearlY = -1;
        int[][] desired = theme == Theme.CLOWN
                ? new int[][]{{2, 2}, {COLS - 3, 2}, {COLS / 2, mazeRows / 2},
                    {2, mazeRows - 5}, {COLS - 3, mazeRows - 5}}
                : new int[][]{{1, 1}, {COLS - 2, 1}, {1, mazeRows - 6}, {COLS - 2, mazeRows - 6}};
        int pearlIndex = theme == Theme.OCEAN && random.nextFloat() < 0.40f
                ? random.nextInt(desired.length) : -1;
        int[] colors = {0xFFFF5B6E, 0xFFFFD34E, 0xFF55D9FF, 0xFF67E58F, 0xFFC083FF};
        List<int[]> used = new ArrayList<>();
        for (int i = 0; i < desired.length; i++) {
            int[] p = nearestDistinctPassable(desired[i][0], desired[i][1], used);
            pellets[p[1]][p[0]] = false;
            powerPellets[p[1]][p[0]] = true;
            if (theme == Theme.CLOWN) clownBallColors[p[1]][p[0]] = colors[i];
            if (theme == Theme.OCEAN && i == pearlIndex) {
                oceanPearlPresent = true;
                oceanPearlX = p[0];
                oceanPearlY = p[1];
            }
            used.add(p);
        }
    }

    private int[] nearestDistinctPassable(int tx, int ty, List<int[]> used) {
        int bestX = 1;
        int bestY = 1;
        int best = Integer.MAX_VALUE;
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (isWall(x, y)) continue;
                if (Math.abs(x - player.cellX) + Math.abs(y - player.cellY) < 4) continue;
                boolean occupied = false;
                for (int[] p : used) {
                    if (Math.abs(p[0] - x) + Math.abs(p[1] - y) < 5) {
                        occupied = true;
                        break;
                    }
                }
                if (occupied) continue;
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

    private void recountPellets() {
        pelletsRemaining = 0;
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) {
                if (pellets[y][x] || powerPellets[y][x]) pelletsRemaining++;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        resetDrawingState();
        if (guardedError != null) {
            drawGuardedError(canvas);
            return;
        }
        try {
            long now = System.nanoTime();
            if (lastFrameNanos == 0L) lastFrameNanos = now;
            float dt = Math.min(0.0667f, Math.max(0f, (now - lastFrameNanos) / 1_000_000_000f));
            lastFrameNanos = now;

            if (screen == Screen.PLAYING && !systemPaused) {
                // Bound movement/collision steps; a slow frame no longer slows the whole game.
                int steps = Math.max(1, (int) Math.ceil(dt * 60f));
                for (int i = 0; i < steps && screen == Screen.PLAYING; i++) updateGame(dt / steps);
            }
            setKeepScreenOn(screen == Screen.PLAYING && !systemPaused);

            switch (screen) {
                case HOME: drawHome(canvas); break;
                case MAP_SELECT: drawMapSelect(canvas); break;
                case GUIDE: drawGuide(canvas); break;
                default: drawGame(canvas); break;
            }

            if (screen == Screen.PLAYING && !systemPaused) postInvalidateOnAnimation();
            else if (screen == Screen.HOME && !systemPaused) postInvalidateDelayed(33L);
        } catch (RuntimeException | LinkageError error) {
            guardedError = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : "：" + error.getMessage());
            screen = Screen.PAUSED;
            resetJoystick();
            drawGuardedError(canvas);
        }
    }

    private void resetDrawingState() {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setColor(Color.WHITE);
        strokePaint.setShader(null);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setAlpha(255);
        textPaint.setShader(null);
        textPaint.setAlpha(255);
    }

    private void drawGuardedError(Canvas canvas) {
        paint.setShader(null);
        paint.setColor(0xFF100B1E);
        canvas.drawRect(0, 0, Math.max(1, viewW), Math.max(1, viewH), paint);
        drawCenteredText(canvas, "游戏已阻止一次崩溃", viewW * 0.5f, viewH * 0.30f,
                viewW * 0.060f, 0xFFFFC857);
        drawCenteredText(canvas, "请截图下面的信息发给我", viewW * 0.5f, viewH * 0.38f,
                viewW * 0.035f, Color.WHITE);
        drawCenteredText(canvas, guardedError == null ? "未知异常" : guardedError,
                viewW * 0.5f, viewH * 0.48f, viewW * 0.027f, 0xFFFF8B9B);
        drawCenteredText(canvas, "轻触屏幕可返回主菜单", viewW * 0.5f, viewH * 0.62f,
                viewW * 0.032f, 0xFFC9CDEC);
    }

    private void updateGame(float dt) {
        gameTime += dt;
        updateBrokenWalls();
        updateRevival();
        updateCompanionRevival();
        updateThemeEvents(dt);
        if (screen != Screen.PLAYING) return;
        updateItems();
        updateBombs();
        updateEscapeSequence();
        if (screen != Screen.PLAYING) return;

        if (player != null && player.alive && !player.forced) {
            float speed = PLAYER_SPEED * (gameTime < speedUntil ? 1.40f : 1f)
                    * (theme == Theme.OCEAN && oceanPearlBoost ? 1.10f : 1f)
                    * factoryConveyorMultiplier(player);
            moveActor(player, dt, speed, false);
        }
        if (screen != Screen.PLAYING) return;
        if (companion != null && companion.alive && !companion.forced) {
            updateCompanionEmergencyTurn();
            float companionSpeed = COMPANION_SPEED * (companionOnly && gameTime < speedUntil ? 1.40f : 1f)
                    * (theme == Theme.OCEAN && oceanPearlBoost ? 1.10f : 1f)
                    * factoryConveyorMultiplier(companion);
            moveActor(companion, dt, companionSpeed, true);
            updateCompanionBomb();
        }

        float progression = 1f + Math.min(0.18f, gameTime / 900f);
        float boost = gameTime >= enemyBoostStart && gameTime < enemyBoostUntil ? 1.50f : 1f;
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || enemy.forced) continue;
            if (theme == Theme.FACTORY && difficulty == Difficulty.HARD) {
                if (isFactoryEnemyCharging(enemy)) continue;
                if (factoryReturningToCharge && factoryAtBase[enemy.kind]) continue;
            }
            float personality = enemy.kind == 4 ? 1f : 1f + enemy.kind * 0.025f;
            float tideBoost = theme == Theme.OCEAN && isUnderwater(enemy.x(), enemy.y()) ? 1.10f : 1f;
            float enemySpeed = BASE_ENEMY_SPEED * progression * boost * personality * tideBoost;
            if (theme == Theme.FACTORY && factoryReturningToCharge
                    && !factoryChargeCompleted[enemy.kind]) {
                enemySpeed = factoryReturnSpeed[enemy.kind];
            }
            enemySpeed *= factoryConveyorMultiplier(enemy);
            moveActor(enemy, dt, enemySpeed, true);
        }

        updateGalaxyPull();
        checkCollisions();
        if (pelletsRemaining <= 0 && screen == Screen.PLAYING && !escapeActive) activateEscape();
    }

    private void moveActor(Actor actor, float dt, float speed, boolean ai) {
        if (!actor.alive || actor.forced || gameTime < actor.stunnedUntil) return;
        if (actor.kind >= 0 && actor.kind <= 4 && gameTime < actor.releaseAt) return;
        if (!ai && !autoMoveMode && !playerJoystickActive) return;

        if (!ai && actor.progress > EPS && actor.wanted == actor.dir.opposite()) {
            int nx = stepX(actor.cellX, actor.cellY, actor.dir);
            int ny = stepY(actor.cellX, actor.cellY, actor.dir);
            actor.cellX = nx;
            actor.cellY = ny;
            actor.progress = 1f - actor.progress;
            actor.dir = actor.wanted;
        }

        // A small late-turn window forgives a direction arriving just after a junction.
        if (!ai && actor.progress > EPS && actor.progress <= Math.min(0.16f, speed * 0.028f)
                && actor.wanted != Dir.NONE && actor.wanted != actor.dir
                && actor.wanted != actor.dir.opposite() && canMove(actor.cellX, actor.cellY, actor.wanted)) {
            actor.progress = 0f;
            actor.dir = actor.wanted;
        }
        float remaining = speed * dt;
        int guard = 0;
        while (remaining > EPS && guard++ < 5) {
            if (actor.progress <= EPS) {
                actor.progress = 0f;
                if (ai) actor.wanted = actor.kind == 10
                        ? chooseExpertDirection(actor) : chooseEnemyDirection(actor);
                if (ai && actor.kind == 10 && expertWantsWait) {
                    actor.dir = Dir.NONE;
                    return;
                }
                if (actor.wanted != Dir.NONE && canMove(actor.cellX, actor.cellY, actor.wanted)) {
                    actor.dir = actor.wanted;
                }
                if (actor.dir == Dir.NONE || !canMove(actor.cellX, actor.cellY, actor.dir)) {
                    if (ai) {
                        actor.dir = chooseAnyDirection(actor, actor.dir.opposite());
                    } else {
                        actor.dir = Dir.NONE;
                    }
                    if (actor.dir == Dir.NONE) return;
                }
            }

            float step = Math.min(remaining, 1f - actor.progress);
            actor.progress += step;
            remaining -= step;
            if (actor.progress >= 1f - EPS) {
                int rawNextX = actor.cellX + actor.dir.dx;
                int rawNextY = actor.cellY + actor.dir.dy;
                if (isTunnelEscapeStep(actor, rawNextX, rawNextY)) {
                    actor.progress = 0f;
                    completeEscape(actor);
                    return;
                }
                int fromX = actor.cellX;
                int fromY = actor.cellY;
                actor.cellX = stepX(fromX, fromY, actor.dir);
                actor.cellY = stepY(fromX, fromY, actor.dir);
                actor.progress = 0f;
                onActorArrived(actor);
                if (screen != Screen.PLAYING || !actor.alive || actor.forced || gameTime < actor.stunnedUntil) return;
            }
        }
    }

    private void onActorArrived(Actor actor) {
        boolean friendlyAi = actor.kind == 10;
        int pelletsBefore = pelletsRemaining;
        if (actor.kind == -1 || friendlyAi) collectPellet(actor);
        if (actor.kind == -1 || friendlyAi && companionOnly) {
            collectItemAt(actor);
        }
        if (friendlyAi) collectGateCrackerAt(actor.cellX, actor.cellY);
        if (actor.kind == 10) recordExpertArrival(actor, pelletsRemaining < pelletsBefore);
        if (actor.kind == -1 || friendlyAi) checkEscapeArrival(actor);
    }

    private void collectPellet(Actor actor) {
        int x = actor.cellX;
        int y = actor.cellY;
        if (pellets[y][x]) {
            pellets[y][x] = false;
            eraseCachedPellet(x, y);
            pelletsRemaining--;
            pelletsEaten++;
            score += actor.kind == 10 ? 8 : 10;
            int maxTools = theme == Theme.FACTORY ? 7 : 5;
            if (pelletsEaten > 0 && pelletsEaten % 80 == 0 && bombCount < maxTools) {
                bombCount++;
                showMessage(theme == Theme.FACTORY ? "收集奖励：十字激光 +1" : theme == Theme.SPRING ? "收集奖励：鞭炮 +1" : "收集奖励：炸弹 +1", 1.6f);
            }
        } else if (powerPellets[y][x]) {
            powerPellets[y][x] = false;
            pelletsRemaining--;
            pelletsEaten++;
            score += theme == Theme.OCEAN ? 80 : 50;
            enemyEatChain = 0;
            if (theme == Theme.OCEAN && x == oceanPearlX && y == oceanPearlY) {
                bubbleUntil = gameTime + 10f;
                breath = 5f;
                oceanPearlBoost = true;
                showMessage("发光珍珠：十秒水下呼吸，本局移动速度永久 +10%", 2.6f);
            } else {
                powerUntil = gameTime + 7f;
                if (theme == Theme.OCEAN) {
                    showMessage("反击贝壳：7秒内可反击敌人", 1.8f);
                } else if (theme == Theme.CLOWN) {
                    clownBallsCollected = Math.min(5, clownBallsCollected + 1);
                    if (clownBallsCollected >= 5) {
                        if (!companionSummoned) activateCompanion("五颗小丑球集齐：召唤小跟班！");
                        else showMessage("五颗小丑球集齐：小跟班已在本局加入", 2f);
                    } else {
                        showMessage("小丑球 " + clownBallsCollected + "/5：7秒内可反击敌人", 1.8f);
                    }
                } else {
                    showMessage("能量觉醒：7秒内可反击敌人", 1.8f);
                }
            }
            playTone(ToneGenerator.TONE_PROP_ACK, 130);
            vibrate(35);
        }
    }

    private void activateEscape() {
        escapeActive = true;
        escapeSpawnAt = gameTime;
        Actor survivor = player != null && player.alive ? player
                : (companion != null && companion.alive ? companion : null);
        if (survivor == null) {
            finishGame(false);
            return;
        }

        switch (theme) {
            case CLOWN: {
                int[] door = chooseEscapeCell(false, false);
                escapeX = door[0];
                escapeY = door[1];
                escapeUnlocked = true;
                // 最后的追逐更紧张，但保留明确预警和短暂安全期。
                enemyBoostStart = gameTime + 1.2f;
                enemyBoostUntil = enemyBoostStart + 5f;
                safeUntil = Math.max(safeUntil, gameTime + 1.2f);
                showMessage("小丑豆子与五颗彩球已集齐！彩幕逃生门出现", 3f);
                break;
            }
            case CLASSIC:
                escapeX = random.nextBoolean() ? 0 : COLS - 1;
                escapeY = 13;
                escapeUnlocked = true;
                showMessage(escapeX == 0 ? "豆子已清空：从左侧隧道逃出" : "豆子已清空：从右侧隧道逃出", 3f);
                break;
            case OCEAN: {
                int[] hatch = chooseEscapeCell(true, false);
                escapeX = hatch[0];
                escapeY = hatch[1];
                escapeUnlocked = true;
                // 逃生舱固定在最高的干燥区域，最后阶段仍要避开潮水抵达顶部。
                showMessage("贝壳已集齐！顶部救生舱开启，离开潮水并进入舱门", 3f);
                break;
            }
            case SPRING: {
                int[] gate = chooseEscapeCell(false, true);
                escapeX = gate[0];
                escapeY = gate[1];
                escapeUnlocked = false;
                spawnGateCracker();
                showMessage("爆竹豆已集齐！先找到金色开门爆竹，再从年门离开", 3.2f);
                break;
            }
            case FACTORY:
                escapeX = FACTORY_DOOR_X;
                escapeY = FACTORY_DOOR_Y;
                escapeUnlocked = true;
                for (int x = FACTORY_DOOR_X - 1; x <= FACTORY_DOOR_X + 1; x++) {
                    walls[FACTORY_DOOR_Y][x] = false;
                }
                wallCacheDirty = true;
                safeUntil = Math.max(safeUntil, gameTime + 1.0f);
                showMessage("齿轮豆已清空：中央机械大门已经开启！", 3f);
                break;
            case GALAXY:
                escapeX = clamp(Math.round(survivor.x()), 0, COLS - 1);
                escapeY = clamp(Math.round(survivor.y()), 0, mazeRows - 1);
                galaxyEscapeAt = gameTime + 1.45f;
                escapeUnlocked = true;
                survivor.place(escapeX, escapeY);
                survivor.stunnedUntil = galaxyEscapeAt;
                if (survivor.kind == -1) invincibleUntil = Math.max(invincibleUntil, galaxyEscapeAt + 0.2f);
                for (Actor enemy : enemies) if (enemy != null) enemy.stunnedUntil = Math.max(enemy.stunnedUntil, galaxyEscapeAt);
                showMessage("小星球已集齐：虫洞正在脚下展开……", 2f);
                break;
        }
        playTone(ToneGenerator.TONE_PROP_ACK, 180);
        vibrate(45);
    }

    private int[] chooseEscapeCell(boolean topOnly, boolean preferBottom) {
        Actor survivor = player != null && player.alive ? player : companion;
        int bestX = 1;
        int bestY = topOnly ? 1 : mazeRows - 2;
        float bestValue = -Float.MAX_VALUE;
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (isWall(x, y) || countExits(x, y) < 1) continue;
                if (topOnly && y > 5) continue;
                if (!topOnly && !(x <= 2 || x >= COLS - 3 || y <= 2 || y >= mazeRows - 3)) continue;
                if (theme == Theme.CLOWN && inClownAttackZone(x, y)) continue;
                float fromSurvivor = survivor == null ? 0f : distance(x, y, survivor.x(), survivor.y());
                float value = fromSurvivor * 1.8f + nearestEnemyDistance(x, y) * 1.2f
                        + countExits(x, y) * 2f + random.nextFloat() * 2f;
                if (preferBottom) value += y * 0.35f;
                if (value > bestValue) {
                    bestValue = value;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

    private void spawnGateCracker() {
        List<int[]> candidates = new ArrayList<>();
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (isWall(x, y) || countExits(x, y) < 2) continue;
                float fromGate = distance(x, y, escapeX, escapeY);
                if (fromGate < 4f || fromGate > 12f || nearestEnemyDistance(x, y) < 3f) continue;
                candidates.add(new int[]{x, y});
            }
        }
        int[] p;
        if (candidates.isEmpty()) p = nearestPassable(COLS / 2, mazeRows / 2);
        else p = candidates.get(random.nextInt(candidates.size()));
        items.add(new Item(ItemType.GATE_CRACKER, p[0], p[1], Float.MAX_VALUE));
    }

    private void armGateCracker(int x, int y) {
        if (gateCrackerArmed || escapeUnlocked) return;
        gateCrackerArmed = true;
        gateCrackerX = x;
        gateCrackerY = y;
        gateCrackerExplodeAt = gameTime + 2.5f;
        showMessage("开门爆竹已点燃：两秒半后开启年门", 2.4f);
        playTone(ToneGenerator.TONE_PROP_PROMPT, 90);
    }

    private void collectGateCrackerAt(int x, int y) {
        Iterator<Item> iterator = items.iterator();
        while (iterator.hasNext()) {
            Item item = iterator.next();
            if (item.type == ItemType.GATE_CRACKER && item.x == x && item.y == y) {
                iterator.remove();
                armGateCracker(x, y);
                return;
            }
        }
    }

    private void updateEscapeSequence() {
        if (!escapeActive) return;
        if (theme == Theme.GALAXY && galaxyEscapeAt > 0f && gameTime >= galaxyEscapeAt) {
            Actor survivor = player != null && player.alive ? player : companion;
            if (survivor == null || !survivor.alive) return;
            galaxyEscapeAt = -1f;
            completeEscape(survivor);
            return;
        }
        if (theme == Theme.SPRING && gateCrackerArmed && !escapeUnlocked
                && gameTime >= gateCrackerExplodeAt) {
            gateCrackerArmed = false;
            escapeUnlocked = true;
            score += 150;
            for (Actor enemy : enemies) if (enemy != null) enemy.stunnedUntil = Math.max(enemy.stunnedUntil, gameTime + 2.5f);
            showMessage("开门爆竹炸响！年门已经开启", 2.5f);
            playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 180);
            vibrate(70);
        }
    }

    private boolean isTunnelEscapeStep(Actor actor, int rawNextX, int rawNextY) {
        if (!escapeActive || !escapeUnlocked
                || (actor.kind != -1 && actor.kind != 10)) return false;
        if (theme == Theme.CLASSIC) {
            if (actor.cellY != escapeY) return false;
            return escapeX == 0 && actor.cellX == 0 && rawNextX < 0
                    || escapeX == COLS - 1 && actor.cellX == COLS - 1 && rawNextX >= COLS;
        }
        return false;
    }

    private void checkEscapeArrival(Actor actor) {
        if (!escapeActive || !escapeUnlocked || theme == Theme.CLASSIC
                || theme == Theme.GALAXY) return;
        if (actor.cellX == escapeX && actor.cellY == escapeY) completeEscape(actor);
    }

    private void completeEscape(Actor actor) {
        if (screen != Screen.PLAYING || actor == null || !actor.alive) return;
        score += actor.kind == 10 ? 400 : 500;
        finishGame(true);
    }

    private boolean canMove(int x, int y, Dir dir) {
        if (dir == Dir.NONE) return false;
        int nx = x + dir.dx;
        int ny = y + dir.dy;
        if (nx < 0 || nx >= COLS) {
            if (isHorizontalTunnel(y)) nx = wrapX(nx);
            else return false;
        }
        if (ny < 0 || ny >= mazeRows) {
            if (isVerticalTunnel(x)) ny = wrapY(ny);
            else return false;
        }
        return !isWall(nx, ny);
    }

    private boolean isHorizontalTunnel(int y) {
        return (theme == Theme.CLASSIC && y == 13)
                || (theme == Theme.FACTORY && tunnelAxis == MazeGenerator.TUNNEL_HORIZONTAL
                && y == tunnelCoordinate);
    }

    private boolean isVerticalTunnel(int x) {
        return theme == Theme.FACTORY && tunnelAxis == MazeGenerator.TUNNEL_VERTICAL
                && x == tunnelCoordinate;
    }

    private int stepX(int x, int y, Dir dir) {
        int nx = x + dir.dx;
        return (nx < 0 || nx >= COLS) && isHorizontalTunnel(y) ? wrapX(nx) : nx;
    }

    private int stepY(int x, int y, Dir dir) {
        int ny = y + dir.dy;
        return (ny < 0 || ny >= mazeRows) && isVerticalTunnel(x) ? wrapY(ny) : ny;
    }

    private boolean isWall(int x, int y) {
        return walls[y][x] && !(brokenUntil[y][x] > gameTime);
    }

    private int wrapX(int x) {
        if (x < 0) return COLS - 1;
        if (x >= COLS) return 0;
        return x;
    }

    private int wrapY(int y) {
        if (y < 0) return mazeRows - 1;
        if (y >= mazeRows) return 0;
        return y;
    }

    private Dir chooseAnyDirection(Actor actor, Dir avoid) {
        Dir[] dirs = CARDINALS;
        List<Dir> choices = new ArrayList<>(4);
        for (Dir d : dirs) if (canMove(actor.cellX, actor.cellY, d) && d != avoid) choices.add(d);
        if (choices.isEmpty()) {
            for (Dir d : dirs) if (canMove(actor.cellX, actor.cellY, d)) choices.add(d);
        }
        return choices.isEmpty() ? Dir.NONE : choices.get(random.nextInt(choices.size()));
    }

    /**
     * 四种敌人分别使用追踪、伏击、夹击与距离切换策略。
     * 路径代价通过实时 BFS 计算，破墙和随机迷宫变化都会立刻反映到决策中。
     */
    private Dir chooseEnemyDirection(Actor enemy) {
        Actor targetActor = player != null && player.alive ? player
                : (companion != null && companion.alive ? companion : null);
        if (targetActor == null) return chooseAnyDirection(enemy, enemy.dir.opposite());

        if (theme == Theme.FACTORY && factoryReturningToCharge
                && enemy.kind >= 0 && enemy.kind < factoryChargeTargetX.length
                && !factoryChargeCompleted[enemy.kind] && !factoryAtBase[enemy.kind]) {
            return chooseFactoryReturnDirection(enemy);
        }

        List<Dir> choices = availableDirections(enemy.cellX, enemy.cellY, enemy.dir.opposite());
        if (choices.isEmpty()) choices = availableDirections(enemy.cellX, enemy.cellY, Dir.NONE);
        if (choices.isEmpty()) return Dir.NONE;

        boolean frightened = gameTime < powerUntil;
        if (frightened) {
            buildDistanceMap(Math.round(targetActor.x()), Math.round(targetActor.y()), bfsDistance);
            Dir best = choices.get(0);
            float bestValue = -Float.MAX_VALUE;
            for (Dir d : choices) {
                int nx = stepX(enemy.cellX, enemy.cellY, d);
                int ny = stepY(enemy.cellX, enemy.cellY, d);
                float value = safeDistanceValue(bfsDistance[ny][nx]) + random.nextFloat() * 2.5f;
                if (value > bestValue) {
                    bestValue = value;
                    best = d;
                }
            }
            return best;
        }

        if (enemy.kind == 4) return choices.get(random.nextInt(choices.size()));

        boolean scatter = gameTime < globalScatterUntil || ((int) (gameTime / 6f) % 4 == 3);
        int targetX;
        int targetY;
        if (scatter) {
            int[][] corners = {{COLS - 2, 1}, {1, 1}, {COLS - 2, mazeRows - 2}, {1, mazeRows - 2}};
            targetX = corners[enemy.kind][0];
            targetY = corners[enemy.kind][1];
        } else if (enemy.kind == 0) {
            targetX = Math.round(targetActor.x());
            targetY = Math.round(targetActor.y());
        } else if (enemy.kind == 1) {
            int[] ahead = projectAhead(targetActor, 4);
            targetX = ahead[0];
            targetY = ahead[1];
        } else if (enemy.kind == 2) {
            int[] ahead = projectAhead(targetActor, 2);
            Actor red = enemies[0];
            float referenceX = red != null ? red.x() : targetActor.x();
            float referenceY = red != null ? red.y() : targetActor.y();
            targetX = clamp(Math.round(ahead[0] * 2f - referenceX), 1, COLS - 2);
            targetY = clamp(Math.round(ahead[1] * 2f - referenceY), 1, mazeRows - 2);
        } else {
            if (actorDistance(enemy, targetActor) > 8f) {
                targetX = Math.round(targetActor.x());
                targetY = Math.round(targetActor.y());
            } else {
                targetX = 1;
                targetY = mazeRows - 2;
            }
        }
        int[] passableTarget = nearestPassable(targetX, targetY);
        buildDistanceMap(passableTarget[0], passableTarget[1], bfsDistance);

        Dir best = choices.get(0);
        float bestValue = Float.MAX_VALUE;
        for (Dir d : choices) {
            int nx = stepX(enemy.cellX, enemy.cellY, d);
            int ny = stepY(enemy.cellX, enemy.cellY, d);
            float value = safeDistanceValue(bfsDistance[ny][nx]);
            value += random.nextFloat() * (enemy.kind == 3 ? 1.0f : 0.22f);
            if (value < bestValue) {
                bestValue = value;
                best = d;
            }
        }
        if (choices.size() > 1 && random.nextFloat() < 0.025f + enemy.kind * 0.008f) {
            best = choices.get(random.nextInt(choices.size()));
        }
        return best;
    }

    /**
     * 小跟班同时考虑豆子距离、敌人预测位置、死胡同、眩晕剩余时间与主题危险。
     * 危急时允许立即掉头；敌人仍会眩晕足够久时，会把其所在通道当成安全窗口穿过去。
     */


    /**
     * 正式版小跟班：纯规则规划。持久目标、敌人到达时间、风险路径与多步生存搜索。
     * 不再每走一格就只比较局部两步，因此不会在等价路线之间来回摇摆。
     */
    private Dir chooseExpertDirection(Actor actor) {
        expertWantsWait = false;
        List<Dir> all = availableDirections(actor.cellX, actor.cellY, Dir.NONE);
        if (all.isEmpty()) return Dir.NONE;

        buildExpertEnemyEta();
        for (int y = 0; y < mazeRows; y++) for (int x = 0; x < COLS; x++) {
            expertHazards[y][x] = companionHazardPenalty(x, y);
            expertExits[y][x] = countExits(x, y);
        }
        boolean powered = gameTime < powerUntil;

        if (theme == Theme.OCEAN && companionOnly && gameTime >= bubbleUntil) {
            buildDryDistanceMap();
            boolean submerged = isUnderwater(actor.x(), actor.y());
            int dryDistance = expertDryDistance[actor.cellY][actor.cellX];
            float returnTime = dryDistance < 0 ? 999f : dryDistance / COMPANION_SPEED;
            if (submerged && breath < returnTime + 1.1f) expertRecoveringBreath = true;
            if (expertRecoveringBreath) {
                if (!submerged && breath >= 4.8f) expertRecoveringBreath = false;
                else if (!submerged) {
                    // Refill only when enemies leave a safe window; otherwise keep evading.
                    if (expertEnemyEta[actor.cellY][actor.cellX] > 1.2f) {
                        expertWantsWait = true;
                        expertPlanLabel = "岸边换气";
                        return Dir.NONE;
                    }
                } else {
                    buildDistanceMap(actor.cellX, actor.cellY, bfsDistance);
                    int tx = -1, ty = -1;
                    float best = Float.MAX_VALUE;
                    for (int y = 0; y < mazeRows; y++) for (int x = 0; x < COLS; x++) {
                        if (bfsDistance[y][x] < 0 || isUnderwater(x, y)) continue;
                        float cost = bfsDistance[y][x] + expertHazards[y][x] * 0.04f;
                        if (expertEnemyEta[y][x] < bfsDistance[y][x] / COMPANION_SPEED + 0.5f) cost += 30f;
                        if (cost < best) { best = cost; tx = x; ty = y; }
                    }
                    Dir air = expertPathDirection(actor, tx, ty, powered);
                    if (air != Dir.NONE) {
                        expertPlanLabel = "返回岸边";
                        return expertArbitrateDirection(actor, air, powered);
                    }
                }
            }
        }

        if (escapeActive) {
            if (theme == Theme.CLASSIC && escapeUnlocked
                    && actor.cellX == escapeX && actor.cellY == escapeY) {
                expertPlanLabel = "穿越出口";
                if (escapeX == 0) return Dir.LEFT;
                if (escapeX == COLS - 1) return Dir.RIGHT;
                if (escapeY == 0) return Dir.UP;
                if (escapeY == mazeRows - 1) return Dir.DOWN;
            }
            int[] exitTarget = expertEscapeTarget();
            if (exitTarget != null) {
                expertPlanLabel = "前往出口";
                Dir escape = expertPathDirection(actor, exitTarget[0], exitTarget[1], powered);
                if (escape != Dir.NONE) return expertArbitrateDirection(actor, escape, powered);
            }
        }

        if (theme == Theme.FACTORY && factoryCountdownStarted && !escapeActive) {
            Dir sweep = chooseFactoryExpertSweep(actor, all);
            if (sweep != Dir.NONE) {
                expertPlanLabel = factoryDeadline - gameTime < 20f ? "极限清扫" : "限时清扫";
                return expertArbitrateDirection(actor, sweep, powered);
            }
        }

        // 反击期间主动追击能够在剩余时间内赶上的敌人；赶不上时继续高效清豆。
        if (powered && !escapeActive && !(theme == Theme.FACTORY && factoryCountdownStarted)) {
            int[] enemyTarget = chooseExpertEnemyTarget(actor);
            if (enemyTarget != null) {
                expertPlanLabel = "反击追击";
                Dir chase = expertPathDirection(actor, enemyTarget[0], enemyTarget[1], true);
                if (chase != Dir.NONE) return expertArbitrateDirection(actor, chase, true);
            }
        }

        boolean stalled = gameTime - expertLastProgressAt > 6.5f;
        if (companionOnly && !escapeActive && !stalled) {
            Dir supply = chooseExpertSupply(actor, powered);
            if (supply != Dir.NONE) return expertArbitrateDirection(actor, supply, powered);
        }
        boolean targetValid = expertTargetX >= 0 && expertTargetY >= 0
                && gameTime < expertTargetUntil
                && (pellets[expertTargetY][expertTargetX] || powerPellets[expertTargetY][expertTargetX]);
        if (!targetValid || stalled) selectExpertTarget(actor, stalled);

        if (expertTargetX >= 0) {
            expertPlanLabel = stalled ? "脱离徘徊" : "安全收集";
            boolean rush = powered;
            if (theme == Theme.FACTORY && factoryCountdownStarted) expertPlanLabel = "限时清扫";
            Dir route = expertPathDirection(actor, expertTargetX, expertTargetY, rush);
            if (route != Dir.NONE) return expertArbitrateDirection(actor, route, powered);
            expertTargetX = expertTargetY = -1;
            selectExpertTarget(actor, true);
            route = expertPathDirection(actor, expertTargetX, expertTargetY,
                    powered);
            if (route != Dir.NONE) return expertArbitrateDirection(actor, route, powered);
        }

        expertPlanLabel = "紧急避险";
        return expertArbitrateDirection(actor, Dir.NONE, powered);
    }

    private void resetExpertPlanner() {
        for (int[] row : expertVisitHeat) Arrays.fill(row, 0);
        expertTargetX = expertTargetY = -1;
        expertTargetUntil = 0f;
        expertLastPelletsRemaining = pelletsRemaining;
        expertLastProgressAt = gameTime;
        expertPlanLabel = "规划中";
        expertEmergencyAt = gameTime;
        expertLastReverseAt = gameTime - 1f;
        expertRecoveringBreath = expertWantsWait = false;
    }

    private void buildDryDistanceMap() {
        for (int[] row : expertDryDistance) Arrays.fill(row, -1);
        int head = 0, tail = 0;
        for (int y = 0; y < mazeRows; y++) for (int x = 0; x < COLS; x++) {
            if (!isWall(x, y) && !isUnderwater(x, y)) {
                expertDryDistance[y][x] = 0;
                queueX[tail] = x; queueY[tail++] = y;
            }
        }
        while (head < tail) {
            int x = queueX[head], y = queueY[head++];
            for (Dir d : CARDINALS) if (canMove(x, y, d)) {
                int nx = stepX(x, y, d), ny = stepY(x, y, d);
                if (expertDryDistance[ny][nx] >= 0) continue;
                expertDryDistance[ny][nx] = expertDryDistance[y][x] + 1;
                queueX[tail] = nx; queueY[tail++] = ny;
            }
        }
    }

    private Dir chooseExpertSupply(Actor actor, boolean powered) {
        if (items.isEmpty()) return Dir.NONE;
        buildDistanceMap(actor.cellX, actor.cellY, bfsDistance);
        Item best = null;
        float bestScore = 0f;
        for (Item item : items) {
            if (item.type != ItemType.HEART && item.type != ItemType.GIFT && item.type != ItemType.WORMHOLE) continue;
            if (item.type == ItemType.HEART && heartUntil > gameTime + 3f) continue;
            int dist = bfsDistance[item.y][item.x];
            if (dist <= 0 || dist > 4 || item.expires - gameTime < dist / COMPANION_SPEED + 0.3f) continue;
            float margin = expertEnemyEta[item.y][item.x] - dist / COMPANION_SPEED;
            if (!powered && margin < 0.7f) continue;
            float value = (item.type == ItemType.WORMHOLE ? 6f : 12f) - dist * 2f - expertHazards[item.y][item.x] * 0.1f;
            if (value > bestScore) { bestScore = value; best = item; }
        }
        if (best == null) return Dir.NONE;
        expertPlanLabel = "拾取补给";
        return expertPathDirection(actor, best.x, best.y, powered);
    }

    private void recordExpertArrival(Actor actor, boolean collected) {
        if (actor == null || actor.kind != 10) return;
        expertVisitHeat[actor.cellY][actor.cellX] = Math.min(30,
                expertVisitHeat[actor.cellY][actor.cellX] + 1);
        if (collected) {
            expertLastPelletsRemaining = pelletsRemaining;
            expertLastProgressAt = gameTime;
            // 取得沿途豆子时不再立刻丢弃长期目标；这是旧版来回徘徊的主要原因。
            // 每取得进展就轻微衰减旧热度，避免永久排斥已走过的主干道。
            if ((pelletsEaten & 15) == 0) {
                for (int y = 0; y < mazeRows; y++) {
                    for (int x = 0; x < COLS; x++) expertVisitHeat[y][x] /= 2;
                }
            }
        }
    }

    private void buildExpertEnemyEta() {
        for (float[] row : expertEnemyEta) Arrays.fill(row, 999f);
        float progression = 1f + Math.min(0.18f, gameTime / 900f);
        float boost = gameTime >= enemyBoostStart && gameTime < enemyBoostUntil ? 1.50f : 1f;
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || enemy.forced || isFactoryEnemyCharging(enemy)) continue;
            float delay = Math.max(0f, Math.max(enemy.releaseAt, enemy.stunnedUntil) - gameTime);
            float personality = enemy.kind == 4 ? 1f : 1f + enemy.kind * 0.025f;
            float predictedSpeed = BASE_ENEMY_SPEED * progression * boost * personality
                    * (theme == Theme.OCEAN && isUnderwater(enemy.x(), enemy.y()) ? 1.10f : 1f)
                    * factoryConveyorMultiplier(enemy);
            buildDistanceMap(enemy.cellX, enemy.cellY, bfsDistance);
            for (int y = 0; y < mazeRows; y++) {
                for (int x = 0; x < COLS; x++) {
                    int d = bfsDistance[y][x];
                    if (d < 0) continue;
                    float eta = delay + Math.max(0f, d - enemy.progress - 0.45f) / Math.max(0.2f, predictedSpeed);
                    if (eta < expertEnemyEta[y][x]) expertEnemyEta[y][x] = eta;
                }
            }
        }
    }

    private int[] chooseExpertEnemyTarget(Actor actor) {
        buildDistanceMap(actor.cellX, actor.cellY, bfsDistance);
        float remaining = powerUntil - gameTime;
        Actor best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || gameTime < enemy.releaseAt) continue;
            int ex = clamp(Math.round(enemy.x()), 0, COLS - 1);
            int ey = clamp(Math.round(enemy.y()), 0, mazeRows - 1);
            int dist = bfsDistance[ey][ex];
            if (dist < 0 || dist > 4) continue;
            if (dist / COMPANION_SPEED > remaining - 1.0f) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = enemy;
            }
        }
        if (best == null) return null;
        int px = clamp(Math.round(best.x() + best.dir.dx * 0.8f), 0, COLS - 1);
        int py = clamp(Math.round(best.y() + best.dir.dy * 0.8f), 0, mazeRows - 1);
        return nearestPassable(px, py);
    }

    private int[] expertEscapeTarget() {
        if (theme == Theme.GALAXY) return null;
        if (theme == Theme.SPRING && !escapeUnlocked) return findGateCracker();
        if (escapeX < 0 || escapeY < 0) return null;
        return new int[]{escapeX, escapeY};
    }

    private void selectExpertTarget(Actor actor, boolean forceFar) {
        buildDistanceMap(actor.cellX, actor.cellY, bfsDistance);
        if (player != null && player.alive) buildDistanceMap(player.cellX, player.cellY, expertPlayerDistance);
        for (int y = 0; y < mazeRows; y++) {
            int row = 0;
            for (int x = 0; x < COLS; x++) {
                row += pellets[y][x] || powerPellets[y][x] ? 1 : 0;
                pelletPrefix[y + 1][x + 1] = pelletPrefix[y][x + 1] + row;
            }
        }
        float bestScore = -Float.MAX_VALUE;
        int bestX = -1;
        int bestY = -1;
        int bestDist = -1;
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) {
                if (!pellets[y][x] && !powerPellets[y][x]) continue;
                int dist = bfsDistance[y][x];
                if (dist < 0) continue;
                int x0 = Math.max(0, x - 2), x1 = Math.min(COLS, x + 3);
                int y0 = Math.max(0, y - 2), y1 = Math.min(mazeRows, y + 3);
                int cluster = pelletPrefix[y1][x1] - pelletPrefix[y0][x1]
                        - pelletPrefix[y1][x0] + pelletPrefix[y0][x0];
                float safety = expertEnemyEta[y][x];
                float arrival = dist / COMPANION_SPEED;
                float targetMargin = safety - arrival;
                float score;
                if (theme == Theme.FACTORY && factoryCountdownStarted) {
                    // 倒计时开始后改用近似最短清扫，不再为“看起来更安全”而大幅绕路。
                    score = -dist * 6.2f + cluster * 0.8f
                            - expertVisitHeat[y][x] * 0.35f;
                    if (factoryConveyors[y][x] != 0) score += 5f;
                } else {
                    score = cluster * 3.2f - dist * (forceFar ? 0.42f : 1.10f)
                            + Math.min(6f, safety) * 2.1f
                            + countExits(x, y) * 2.4f
                            - expertVisitHeat[y][x] * (forceFar ? 10f : 5f);
                }
                if (gameTime >= powerUntil) {
                    if (targetMargin < 0.15f) score -= 520f;
                    else if (targetMargin < 0.85f) score -= (0.85f - targetMargin) * 170f;
                    else score += Math.min(3.5f, targetMargin) * 4.5f;
                }
                if (powerPellets[y][x] && gameTime >= powerUntil) score += 48f;
                if (countExits(x, y) <= 1 && safety < 4f) score -= 70f;
                if (theme == Theme.OCEAN && isUnderwater(x, y) && gameTime >= bubbleUntil) {
                    score -= Math.max(0f, 3.6f - breath) * 30f;
                    if (companionOnly) {
                        int back = expertDryDistance[y][x];
                        float budget = isUnderwater(actor.x(), actor.y()) ? breath : 5f;
                        if (back < 0 || (dist + back) / COMPANION_SPEED + 0.8f > budget) score -= 800f;
                    }
                }
                if (theme == Theme.FACTORY && factoryCountdownStarted) {
                    float remaining = factoryDeadline - gameTime;
                    if (remaining < 25f) score -= dist * 1.2f;
                }
                if (player != null && player.alive) {
                    int playerDist = expertPlayerDistance[y][x];
                    if (playerDist >= 0 && playerDist <= 4 && playerDist + 1 < dist) score -= 24f;
                }
                score -= expertHazards[y][x] * 0.65f;
                if (score > bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestY = y;
                    bestDist = dist;
                }
            }
        }
        expertTargetX = bestX;
        expertTargetY = bestY;
        float travel = bestDist < 0 ? 4f : bestDist / COMPANION_SPEED;
        expertTargetUntil = gameTime + Math.max(forceFar ? 9f : 7f, travel + 4.5f);
        if (forceFar) expertLastProgressAt = gameTime;
    }

    private Dir chooseFactoryExpertSweep(Actor actor, List<Dir> all) {
        buildPelletDistanceMap();
        Dir best = Dir.NONE;
        float bestScore = Float.POSITIVE_INFINITY;
        for (Dir d : all) {
            int nx = stepX(actor.cellX, actor.cellY, d);
            int ny = stepY(actor.cellX, actor.cellY, d);
            int dist = pelletDistance[ny][nx] < 0 ? 999 : pelletDistance[ny][nx];
            float score = dist * 12f;
            if (pellets[ny][nx] || powerPellets[ny][nx]) score -= 20f;
            byte belt = factoryConveyors[ny][nx];
            if (belt != 0) {
                boolean aligned = belt == 1 ? (d == Dir.LEFT || d == Dir.RIGHT)
                        : (d == Dir.UP || d == Dir.DOWN);
                score -= aligned ? 7f : 2f;
            }
            score += expertVisitHeat[ny][nx] * 0.04f;
            if (d == actor.dir) score -= 0.8f;
            if (d == actor.dir.opposite() && countExits(actor.cellX, actor.cellY) > 1) score += 0.30f;
            if (score < bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private Dir expertPathDirection(Actor actor, int tx, int ty, boolean aggressive) {
        if (tx < 0 || ty < 0 || tx >= COLS || ty >= mazeRows || isWall(tx, ty)) return Dir.NONE;
        for (float[] row : expertPathCost) Arrays.fill(row, Float.POSITIVE_INFINITY);
        Arrays.fill(expertHeapPosition, -1);
        expertHeapSize = 0;
        int start = actor.cellY * COLS + actor.cellX;
        expertPathCost[actor.cellY][actor.cellX] = 0f;
        expertPathArrival[actor.cellY][actor.cellX] = 0f;
        expertRouteFirst[start] = Dir.NONE;
        pushExpertHeap(start);
        while (expertHeapSize > 0) {
            int id = popExpertHeap(), x = id % COLS, y = id / COLS;
            if (x == tx && y == ty) return expertRouteFirst[id];
            for (Dir d : CARDINALS) {
                if (!canMove(x, y, d)) continue;
                int nx = stepX(x, y, d), ny = stepY(x, y, d), nid = ny * COLS + nx;
                if (expertHeapPosition[nid] == -2) continue;
                float travel = expertStepSeconds(x, y, d);
                float arrival = expertPathArrival[y][x] + travel;
                float margin = expertEnemyEta[ny][nx] - arrival;
                boolean protectedArrival = powerUntil > gameTime + arrival + 0.45f;
                boolean rush = theme == Theme.FACTORY && factoryCountdownStarted;
                float cost = travel * COMPANION_SPEED + expertHazards[ny][nx] * 0.045f;
                if (!protectedArrival) {
                    if (margin < 0.10f) cost += 500f;
                    else if (margin < 0.65f) cost += (0.65f - margin) * 180f;
                    else if (margin < 1.8f) cost += (1.8f - margin) * 18f;
                }
                if (!rush && expertExits[ny][nx] <= 1 && (nx != tx || ny != ty)) cost += 10f;
                cost += expertVisitHeat[ny][nx] * (rush ? 0.05f : 0.45f);
                if (id == start && d == actor.dir.opposite()) cost += 0.8f;
                float candidate = expertPathCost[y][x] + Math.max(0.08f, cost);
                if (candidate + EPS < expertPathCost[ny][nx]) {
                    expertPathCost[ny][nx] = candidate;
                    expertPathArrival[ny][nx] = arrival;
                    expertRouteFirst[nid] = id == start ? d : expertRouteFirst[id];
                    pushExpertHeap(nid);
                }
            }
        }
        return Dir.NONE;
    }

    private float expertStepSeconds(int x, int y, Dir d) {
        float speed = COMPANION_SPEED * (theme == Theme.OCEAN && oceanPearlBoost ? 1.10f : 1f);
        if (companionOnly && gameTime < speedUntil) speed *= 1.40f;
        byte belt = factoryConveyors[y][x];
        if (theme == Theme.FACTORY && belt != 0) speed *= 2.5f;
        return 1f / speed;
    }

    private float heapCost(int id) { return expertPathCost[id / COLS][id % COLS]; }

    private void pushExpertHeap(int id) {
        int pos = expertHeapPosition[id];
        if (pos < 0) pos = expertHeapSize++;
        while (pos > 0) {
            int parent = (pos - 1) / 2, previous = expertHeap[parent];
            if (heapCost(previous) <= heapCost(id)) break;
            expertHeap[pos] = previous;
            expertHeapPosition[previous] = pos;
            pos = parent;
        }
        expertHeap[pos] = id;
        expertHeapPosition[id] = pos;
    }

    private int popExpertHeap() {
        int result = expertHeap[0], last = expertHeap[--expertHeapSize];
        expertHeapPosition[result] = -2;
        if (expertHeapSize > 0) {
            int pos = 0;
            while (pos * 2 + 1 < expertHeapSize) {
                int child = pos * 2 + 1;
                if (child + 1 < expertHeapSize && heapCost(expertHeap[child + 1]) < heapCost(expertHeap[child])) child++;
                if (heapCost(last) <= heapCost(expertHeap[child])) break;
                expertHeap[pos] = expertHeap[child];
                expertHeapPosition[expertHeap[pos]] = pos;
                pos = child;
            }
            expertHeap[pos] = last;
            expertHeapPosition[last] = pos;
        }
        return result;
    }

    /**
     * 对长期规划给出的第一步再做一次9步时间展开检查。
     * 这里只改变决策，不提供免伤、眩晕或额外时间。
     */
    private Dir expertArbitrateDirection(Actor actor, Dir preferred, boolean powered) {
        if (actor == null) return Dir.NONE;
        if (preferred != Dir.NONE && canMove(actor.cellX, actor.cellY, preferred)) {
            int px = stepX(actor.cellX, actor.cellY, preferred);
            int py = stepY(actor.cellX, actor.cellY, preferred);
            float immediateMargin = expertEnemyEta[py][px] - 1f / COMPANION_SPEED;
            float nearest = nearestEnemyDistance(Math.round(actor.x()), Math.round(actor.y()));
            float hazard = companionHazardPenalty(px, py);
            // 局面宽松时尊重长期目标，避免每个路口都重新摇摆。
            if ((powerUntil > gameTime + 1.8f || nearest > 5.2f) && hazard < 55f
                    && (powerUntil > gameTime + 1.8f || immediateMargin > 1.25f)) return preferred;
        }
        Dir beam = chooseExpertBeamDirection(actor, preferred, powered);
        if (beam != Dir.NONE) return beam;
        if (preferred != Dir.NONE && canMove(actor.cellX, actor.cellY, preferred)) return preferred;
        return chooseExpertSurvivalDirection(actor,
                availableDirections(actor.cellX, actor.cellY, Dir.NONE));
    }

    private Dir chooseExpertBeamDirection(Actor actor, Dir preferred, boolean powered) {
        ExpertRolloutState[] beam = rolloutPool[0], next = rolloutPool[1];
        ExpertRolloutState root = beam[0];
        root.x = actor.cellX; root.y = actor.cellY; root.dir = actor.dir;
        root.first = Dir.NONE; root.steps = 0; root.score = 0f; root.arrival = 0f;
        root.minMargin = 999f; root.powerEnd = powerUntil;
        root.route[0] = root.y * COLS + root.x;
        int count = 1;
        final int width = 32;
        for (int depth = 1; depth <= 9; depth++) {
            int nextCount = 0;
            for (int i = 0; i < count; i++) {
                ExpertRolloutState state = beam[i];
                for (Dir d : CARDINALS) {
                    if (!canMove(state.x, state.y, d)) continue;
                    int nx = stepX(state.x, state.y, d), ny = stepY(state.x, state.y, d);
                    int id = ny * COLS + nx;
                    boolean repeated = false;
                    for (int k = 0; k < depth; k++) if (state.route[k] == id) { repeated = true; break; }
                    float arrival = state.arrival + expertStepSeconds(state.x, state.y, d);
                    boolean safePower = state.powerEnd > gameTime + arrival + 0.35f;
                    float margin = safePower ? 6f : expertEnemyEta[ny][nx] - arrival;
                    int exits = expertExits[ny][nx];
                    float gain = Math.min(5f, margin) * 7.5f + exits * 2.8f
                            - expertHazards[ny][nx] * 0.72f - expertVisitHeat[ny][nx] * 0.55f;
                    if (!safePower) {
                        if (margin < 0.10f) gain -= 900f;
                        else if (margin < 0.65f) gain -= (0.65f - margin) * 260f;
                        else if (margin < 1.35f) gain -= (1.35f - margin) * 38f;
                    }
                    if (repeated) gain -= 12f;
                    else if (pellets[ny][nx]) gain += theme == Theme.FACTORY && factoryCountdownStarted ? 12f : 4.5f;
                    float nextPowerEnd = state.powerEnd;
                    if (!repeated && powerPellets[ny][nx] && !(theme == Theme.OCEAN && nx == oceanPearlX && ny == oceanPearlY)) {
                        gain += safePower ? 4f : 24f;
                        nextPowerEnd = gameTime + arrival + 7f;
                    }
                    if (exits <= 1 && !safePower && margin < 2.2f) gain -= 55f;
                    if (d == state.dir.opposite() && exits > 1) gain -= 4f;
                    Dir first = depth == 1 ? d : state.first;
                    if (first == preferred) gain += depth == 1 ? 14f : 1.2f;
                    if (d == state.dir) gain += 0.7f;
                    ExpertRolloutState candidate = next[nextCount++];
                    candidate.x = nx; candidate.y = ny; candidate.dir = d; candidate.first = first;
                    candidate.steps = depth; candidate.arrival = arrival; candidate.powerEnd = nextPowerEnd;
                    candidate.minMargin = Math.min(state.minMargin, margin);
                    candidate.score = state.score + gain;
                    System.arraycopy(state.route, 0, candidate.route, 0, depth);
                    candidate.route[depth] = id;
                }
            }
            if (nextCount == 0) break;
            Arrays.sort(next, 0, nextCount, ROLLOUT_ORDER);
            count = Math.min(width, nextCount);
            ExpertRolloutState[] swap = beam; beam = next; next = swap;
        }
        return beam[0].first;
    }

    private Dir chooseExpertSurvivalDirection(Actor actor, List<Dir> all) {
        if (all.isEmpty()) return Dir.NONE;
        Dir best = all.get(0);
        float bestScore = -Float.MAX_VALUE;
        for (Dir d : all) {
            int nx = stepX(actor.cellX, actor.cellY, d);
            int ny = stepY(actor.cellX, actor.cellY, d);
            float arrival = 1f / COMPANION_SPEED;
            float margin = expertEnemyEta[ny][nx] - arrival;
            float score = margin * 80f - companionHazardPenalty(nx, ny) * 1.5f
                    + countExits(nx, ny) * 12f - expertVisitHeat[ny][nx] * 5f;
            buildDistanceMap(nx, ny, bfsDistance);
            float reachableSafety = 0f;
            for (int y = 0; y < mazeRows; y++) {
                for (int x = 0; x < COLS; x++) {
                    int dist = bfsDistance[y][x];
                    if (dist >= 0 && dist <= 6) {
                        reachableSafety = Math.max(reachableSafety,
                                expertEnemyEta[y][x] - dist / COMPANION_SPEED);
                    }
                }
            }
            score += reachableSafety * 24f;
            if (d == actor.dir) score += 3f;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }


    private int[] findGateCracker() {
        for (Item item : items) {
            if (item.type == ItemType.GATE_CRACKER) return new int[]{item.x, item.y};
        }
        return null;
    }

    private float companionHazardPenalty(int x, int y) {
        float penalty = 0f;
        if (theme == Theme.CLOWN && inClownAttackZone(x, y)) penalty += 24f;
        if (theme == Theme.OCEAN && tideLevel > 0.08f) {
            float waterRow = mazeRows * (1f - tideLevel);
            if (y > waterRow) penalty += 12f + (y - waterRow) * 1.5f;
        }
        for (Bomb b : activeBombs) {
            if (b.big && !b.exploded && b.explodeAt - gameTime < 3.2f
                    && distance(x, y, b.x, b.y) < b.radius + 1.2f) penalty += 180f;
        }
        if (meteor.active && !meteor.impacted && meteor.impactAt - gameTime < 1.7f
                && distance(x, y, meteor.x, meteor.y) < 2.3f) penalty += 80f;
        if (theme == Theme.FACTORY && factoryTurretCharging && gameTime < factoryTurretFireAt
                && pointNearFactoryTurretBeam(x, y, 0.78f)) {
            float urgency = clamp01((gameTime - factoryTurretChargeStart)
                    / Math.max(0.1f, factoryTurretFireAt - factoryTurretChargeStart));
            penalty += 80f + urgency * 170f;
        }
        return penalty;
    }

    private List<Dir> availableDirections(int x, int y, Dir avoid) {
        Dir[] dirs = CARDINALS;
        List<Dir> out = new ArrayList<>(4);
        for (Dir d : dirs) {
            if (d != avoid && canMove(x, y, d)) out.add(d);
        }
        return out;
    }

    private int[] projectAhead(Actor actor, int steps) {
        int x = Math.round(actor.x());
        int y = Math.round(actor.y());
        Dir dir = actor.dir == Dir.NONE ? actor.wanted : actor.dir;
        for (int i = 0; i < steps && dir != Dir.NONE; i++) {
            if (!canMove(x, y, dir)) break;
            int oldX = x;
            int oldY = y;
            x = stepX(oldX, oldY, dir);
            y = stepY(oldX, oldY, dir);
        }
        return new int[]{x, y};
    }

    private void buildDistanceMap(int tx, int ty, int[][] output) {
        for (int[] row : output) Arrays.fill(row, -1);
        int[] target = nearestPassable(tx, ty);
        int head = 0;
        int tail = 0;
        queueX[tail] = target[0];
        queueY[tail++] = target[1];
        output[target[1]][target[0]] = 0;
        Dir[] dirs = CARDINALS;
        while (head < tail) {
            int x = queueX[head];
            int y = queueY[head++];
            int nextDist = output[y][x] + 1;
            for (Dir d : dirs) {
                if (!canMove(x, y, d)) continue;
                int nx = stepX(x, y, d);
                int ny = stepY(x, y, d);
                if (output[ny][nx] >= 0) continue;
                output[ny][nx] = nextDist;
                queueX[tail] = nx;
                queueY[tail++] = ny;
            }
        }
    }

    private void buildPelletDistanceMap() {
        for (int[] row : pelletDistance) Arrays.fill(row, -1);
        int head = 0;
        int tail = 0;
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) {
                if (pellets[y][x] || powerPellets[y][x]) {
                    pelletDistance[y][x] = 0;
                    queueX[tail] = x;
                    queueY[tail++] = y;
                }
            }
        }
        Dir[] dirs = CARDINALS;
        while (head < tail) {
            int x = queueX[head];
            int y = queueY[head++];
            int nd = pelletDistance[y][x] + 1;
            for (Dir d : dirs) {
                if (!canMove(x, y, d)) continue;
                int nx = stepX(x, y, d);
                int ny = stepY(x, y, d);
                if (pelletDistance[ny][nx] >= 0) continue;
                pelletDistance[ny][nx] = nd;
                queueX[tail] = nx;
                queueY[tail++] = ny;
            }
        }
    }

    private int[] nearestPassable(int tx, int ty) {
        tx = clamp(tx, 0, COLS - 1);
        ty = clamp(ty, 0, mazeRows - 1);
        if (!isWall(tx, ty)) return new int[]{tx, ty};
        int bestX = player == null ? 1 : player.spawnX;
        int bestY = player == null ? mazeRows - 2 : player.spawnY;
        int best = Integer.MAX_VALUE;
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) {
                if (isWall(x, y)) continue;
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

    private int safeDistanceValue(int d) { return d < 0 ? 9999 : d; }

    private int countExits(int x, int y) {
        int count = 0;
        for (Dir d : CARDINALS) {
            if (canMove(x, y, d)) count++;
        }
        return count;
    }

    private float nearestEnemyDistance(int x, int y) {
        float best = 1000f;
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || gameTime < enemy.stunnedUntil
                    || gameTime < enemy.releaseAt) continue;
            float predictedX = enemy.x() + enemy.dir.dx * 1.2f;
            float predictedY = enemy.y() + enemy.dir.dy * 1.2f;
            float dx = Math.abs(x - predictedX);
            float dy = Math.abs(y - predictedY);
            if (isHorizontalTunnel(enemy.cellY)) dx = Math.min(dx, COLS - dx);
            if (theme == Theme.FACTORY && tunnelAxis == MazeGenerator.TUNNEL_VERTICAL) {
                dy = Math.min(dy, mazeRows - dy);
            }
            best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return best;
    }

    /**
     * 每次复活都从一批远离存活敌人的开放格中随机挑选，避免固定出生点被反复蹲守。
     */
    private int[] chooseSafeRandomRespawn() {
        float farthest = -1f;
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (isWall(x, y) || countExits(x, y) < 2) continue;
                float d = rawEnemyDistance(x, y);
                if (d > farthest) farthest = d;
            }
        }
        List<int[]> candidates = new ArrayList<>();
        float threshold = Math.max(4.5f, farthest - 2.5f);
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (isWall(x, y) || countExits(x, y) < 2) continue;
                if (rawEnemyDistance(x, y) < threshold) continue;
                if (theme == Theme.FACTORY && x == factoryTurretX && y == factoryTurretY) continue;
                candidates.add(new int[]{x, y});
            }
        }
        if (candidates.isEmpty()) return safestFloorNearPlayer();
        return candidates.get(random.nextInt(candidates.size()));
    }

    private float rawEnemyDistance(int x, int y) {
        float best = 1000f;
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive) continue;
            float dx = Math.abs(x - enemy.x());
            float dy = Math.abs(y - enemy.y());
            if (isHorizontalTunnel(enemy.cellY)) dx = Math.min(dx, COLS - dx);
            if (theme == Theme.FACTORY && tunnelAxis == MazeGenerator.TUNNEL_VERTICAL) {
                dy = Math.min(dy, mazeRows - dy);
            }
            best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return best;
    }

    private void updateThemeEvents(float dt) {
        switch (theme) {
            case CLOWN: updateClownEvents(); break;
            case CLASSIC: updateClassicEvents(); break;
            case OCEAN: updateOceanEvents(dt); break;
            case SPRING: updateSpringEvents(dt); break;
            case GALAXY: updateGalaxyEvents(); break;
            case FACTORY: updateFactoryEvents(dt); break;
        }
    }

    private void updateClownEvents() {
        if (gameTime >= nextGiftAt) {
            nextGiftAt = gameTime + 20f + random.nextFloat() * 20f;
            if (random.nextFloat() < 0.58f && !hasItem(ItemType.GIFT)) {
                spawnSafeItem(ItemType.GIFT, 11f, 6);
            }
        }
        if (gameTime >= nextClownBuffAt) {
            nextClownBuffAt = gameTime + 20f + random.nextFloat() * 20f;
            // 敌人过近时延后，避免隐身瞬间形成无预警必死碰撞。
            Actor target = player != null && player.alive ? player : companion;
            float nearest = target == null ? 99f : nearestEnemyDistance(Math.round(target.x()), Math.round(target.y()));
            if (random.nextFloat() < 0.50f && nearest > 4.2f) {
                clownWaveStart = gameTime;
                enemyBoostStart = gameTime + 1.15f;
                enemyBoostUntil = enemyBoostStart + 5f;
                enemyInvisibleStart = gameTime + 1.15f;
                enemyInvisibleUntil = enemyInvisibleStart + 4f;
                for (Actor e : enemies) if (e != null) e.revealed = false;
                showMessage("小丑挥手：敌人加速并即将隐形！", 2.1f);
                playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 180);
            }
        }

        if (player != null && player.alive) {
            boolean inRange = inClownAttackZone(player.x(), player.y());
            if (inRange && !wasInClownRange && gameTime > clownAttackStart + 2f
                    && random.nextFloat() < 0.15f) {
                startClownAttack(player.x(), player.y());
            }
            wasInClownRange = inRange;
            float elapsed = gameTime - clownAttackStart;
            if (!clownAttackHit && elapsed >= 0.68f && elapsed <= 1.12f
                    && playerInsideClownStrike(player.x(), player.y())) {
                clownAttackHit = true;
                damagePlayer("被小丑的手臂击中");
            }
        }
    }

    private boolean inClownAttackZone(float x, float y) {
        boolean horizontal = y >= 12.5f && y <= 15.5f && (x >= 8.5f && x <= 10.5f || x >= 13.5f && x <= 15.5f);
        boolean vertical = x >= 10.5f && x <= 13.5f && (y >= 10.5f && y <= 12.5f || y >= 15.5f && y <= 17.5f);
        return horizontal || vertical;
    }

    private void startClownAttack(float px, float py) {
        float dx = px - 12f;
        float dy = py - 14f;
        if (Math.abs(dx) > Math.abs(dy)) clownAttackDir = dx < 0 ? Dir.LEFT : Dir.RIGHT;
        else clownAttackDir = dy < 0 ? Dir.UP : Dir.DOWN;
        clownAttackStart = gameTime;
        clownAttackHit = false;
        showMessage("小丑攻击预警——快离开红色范围！", 1.2f);
        vibrate(25);
    }

    private boolean playerInsideClownStrike(float x, float y) {
        switch (clownAttackDir) {
            case LEFT: return x >= 8.4f && x <= 10.8f && y >= 12.4f && y <= 15.6f;
            case RIGHT: return x >= 13.2f && x <= 15.6f && y >= 12.4f && y <= 15.6f;
            case UP: return y >= 10.4f && y <= 12.8f && x >= 10.4f && x <= 13.6f;
            case DOWN: return y >= 15.2f && y <= 17.6f && x >= 10.4f && x <= 13.6f;
            default: return false;
        }
    }

    private void updateClassicEvents() {
        if (gameTime >= nextHeartAt) {
            nextHeartAt = gameTime + 24f + random.nextFloat() * 18f;
            if (random.nextFloat() < 0.42f && !hasItem(ItemType.HEART) && gameTime >= heartUntil) {
                spawnSafeItem(ItemType.HEART, 10f, 8);
            }
        }
        if (difficulty == Difficulty.HARD && !escapeActive && gameTime >= nextClassicShiftAt) {
            nextClassicShiftAt = gameTime + 72f + random.nextFloat() * 48f;
            // 间隔很长且只有约三成概率真正变化，避免频繁打断正常路线判断。
            if (random.nextFloat() < 0.30f) transformClassicMaze();
        }
    }

    private void transformClassicMaze() {
        int total = pelletsRemaining;
        if (total <= 0) return;
        int powerCount = 0;
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) if (powerPellets[y][x]) powerCount++;
        }

        MazeGenerator.Result replacement = null;
        for (int attempt = 0; attempt < 16; attempt++) {
            MazeGenerator.Result candidate = MazeGenerator.generate(Theme.CLASSIC.ordinal(),
                    System.nanoTime() + attempt * 15485863L);
            if (MazeGenerator.countFloors(candidate.walls) >= total + 18) {
                replacement = candidate;
                break;
            }
        }
        if (replacement == null) return;

        walls = replacement.walls;
        mazeRows = replacement.activeRows;
        mazeVisualScale = replacement.visualScale;
        tunnelAxis = replacement.tunnelAxis;
        tunnelCoordinate = replacement.tunnelCoordinate;
        brokenUntil = new float[ROWS][COLS];
        brokenWallCount = 0;
        updateMazeGeometry();

        relocateActorAfterShift(player, false, 1, mazeRows - 2);
        relocateActorAfterShift(companion, false, 4, mazeRows - 2);
        for (int i = 0; i < enemies.length; i++) {
            Actor enemy = enemies[i];
            if (enemy == null) continue;
            int[] spawn = nearestPassable(COLS / 2 + (i % 2 == 0 ? -1 : 1), mazeRows / 2 + i / 2);
            relocateActorAfterShift(enemy, true, spawn[0], spawn[1]);
            enemy.stunnedUntil = Math.max(enemy.stunnedUntil, gameTime + 1.25f);
        }

        pellets = new boolean[ROWS][COLS];
        powerPellets = new boolean[ROWS][COLS];
        List<int[]> cells = new ArrayList<>();
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) {
                if (isWall(x, y) || occupiedByActor(x, y)) continue;
                cells.add(new int[]{x, y});
            }
        }
        if (cells.size() < total) {
            cells.clear();
            for (int y = 0; y < mazeRows; y++) {
                for (int x = 0; x < COLS; x++) if (!isWall(x, y)) cells.add(new int[]{x, y});
            }
        }
        Collections.shuffle(cells, random);
        powerCount = Math.min(powerCount, total);
        for (int n = 0; n < total && n < cells.size(); n++) {
            int[] c = cells.get(n);
            if (n < powerCount) powerPellets[c[1]][c[0]] = true;
            else pellets[c[1]][c[0]] = true;
        }
        pelletsRemaining = Math.min(total, cells.size());
        resetExpertPlanner();
        safeUntil = Math.max(safeUntil, gameTime + 1.4f);
        wallCacheDirty = true;
        showMessage("经典迷宫突然重组！豆子总量保持不变", 2.4f);
        playTone(ToneGenerator.TONE_PROP_PROMPT, 120);
        vibrate(45);
    }

    private void relocateActorAfterShift(Actor actor, boolean resetSpawn, int fallbackX, int fallbackY) {
        if (actor == null) return;
        int desiredX = clamp(Math.round(actor.x()), 0, COLS - 1);
        int desiredY = clamp(Math.round(actor.y()), 0, mazeRows - 1);
        int[] p = nearestPassable(desiredX, desiredY);
        actor.place(p[0], p[1]);
        if (resetSpawn) {
            int[] spawn = nearestPassable(fallbackX, fallbackY);
            actor.spawnX = spawn[0];
            actor.spawnY = spawn[1];
        }
    }

    private boolean occupiedByActor(int x, int y) {
        if (player != null && player.alive && player.cellX == x && player.cellY == y) return true;
        if (companion != null && companion.alive && companion.cellX == x && companion.cellY == y) return true;
        for (Actor enemy : enemies) {
            if (enemy != null && enemy.alive && enemy.cellX == x && enemy.cellY == y) return true;
        }
        return false;
    }

    private void updateFactoryEvents(float dt) {
        updateFactoryManufacturing(dt);
        updateFactoryArmAttack();
        updateFactoryChargeCycle();
        updateFactoryLaserTurret();

        if (!factoryCountdownStarted && factoryInitialPellets > 0
                && pelletsRemaining <= factoryInitialPellets / 2) {
            factoryCountdownStarted = true;
            int seconds = difficulty == Difficulty.EASY ? 120
                    : difficulty == Difficulty.HARD ? 80 : 100;
            factoryDeadline = gameTime + seconds;
            lastFactorySecond = seconds;
            showMessage("工厂封锁倒计时启动：" + seconds + "秒内完成收集并逃出", 3f);
            playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 190);
            vibrate(55);
        }
        if (!factoryCountdownStarted) return;
        int left = Math.max(0, (int) Math.ceil(factoryDeadline - gameTime));
        if (left != lastFactorySecond) {
            lastFactorySecond = left;
            if (left == 60 || left == 30 || left == 10 || left <= 5 && left > 0) {
                showMessage("机械工厂剩余 " + left + " 秒", left <= 10 ? 1.0f : 1.6f);
                if (left <= 10) playTone(ToneGenerator.TONE_PROP_PROMPT, 70);
            }
        }
        if (gameTime >= factoryDeadline && screen == Screen.PLAYING) {
            failureReason = "工厂封锁：未能在规定时间内逃出";
            finishGame(false);
        }
    }

    private void updateOceanEvents(float dt) {
        if (!tideWarned && tideRising && gameTime >= nextTideAt - 3f) {
            tideWarned = true;
            showMessage("涨潮预警：三秒后海水上升", 2.4f);
            playTone(ToneGenerator.TONE_PROP_PROMPT, 100);
        }
        if (gameTime >= nextTideAt) {
            tideWarned = false;
            if (tideRising) {
                tideStage = Math.min(3, tideStage + 1);
                tideTarget = tideStage * 0.20f;
                showMessage("潮位上升至 " + Math.round(tideTarget * 100f) + "%", 1.5f);
                // 每次正常涨潮后，下一次才有小概率短暂退潮。
                tideRising = !(tideTarget > 0.15f && random.nextFloat() < 0.20f);
                nextTideAt = gameTime + 17f + random.nextFloat() * 7f;
            } else {
                // 极低概率完全退潮；无论退多少都不降低 tideStage，下一次仍按既有进度继续涨。
                boolean fullRetreat = random.nextFloat() < 0.08f;
                tideTarget = fullRetreat ? 0f : Math.max(0f, (tideStage - 1) * 0.20f);
                tideRising = true;
                nextTideAt = gameTime + (fullRetreat ? 13f : 11f) + random.nextFloat() * 5f;
                showMessage(fullRetreat
                        ? "罕见大退潮：下一次涨潮仍按原进度继续"
                        : "短暂退潮：下一次涨潮仍会继续抬高", 1.9f);
            }
        }
        tideLevel = approach(tideLevel, tideTarget, dt * 0.095f);
        Actor diver = player != null && player.alive ? player
                : companionOnly && companion != null && companion.alive ? companion : null;
        if (diver != null && isUnderwater(diver.x(), diver.y())) {
            if (gameTime < bubbleUntil) {
                breath = 5f;
            } else {
                breath -= dt;
                if (breath <= 0f) {
                    breath = 5f;
                    if (diver == player) damagePlayer("氧气耗尽");
                    else damageCompanion("氧气耗尽");
                }
            }
        } else {
            breath = Math.min(5f, breath + dt * 2.4f);
        }
    }

    private boolean isUnderwater(float x, float y) {
        return tideLevel > 0.015f && y + 0.35f > mazeRows * (1f - tideLevel);
    }

    private void updateSpringEvents(float dt) {
        if (gameTime >= nextBigCrackerAt) {
            nextBigCrackerAt = gameTime + 20f + random.nextFloat() * 16f;
            if (!hasItem(ItemType.BIG_CRACKER) && !hasArmedBigCracker()) {
                spawnSafeItem(ItemType.BIG_CRACKER, 8f, 3);
            }
        }
    }

    private void updateSpringDragon(float dt) {
        Actor target = player != null && player.alive ? player
                : companion != null && companion.alive ? companion : null;
        if (!dragonDashing && target != null && gameTime >= nextDragonDashAt) {
            dragonDashing = true;
            dragonCorrected = false;
            dragonHitApplied = false;
            dragonWarningStart = gameTime;
            dragonMoveStart = gameTime + 0.72f;
            dragonSegmentStartAt = dragonMoveStart;
            dragonDashEnd = dragonMoveStart + 1.62f;
            dragonStartX = random.nextFloat() * (COLS - 2f) + 1f;
            dragonStartY = mazeRows + 2.4f;
            int[] ahead = projectAhead(target, 2);
            dragonAimX = clamp(ahead[0], 1, COLS - 2);
            dragonAimY = clamp(ahead[1], 1, mazeRows - 2);
            float[] exit = dragonExitBeyondMap(dragonStartX, dragonStartY, dragonAimX, dragonAimY);
            dragonTargetX = exit[0];
            dragonTargetY = exit[1];
            dragonCurrentX = dragonStartX;
            dragonCurrentY = dragonStartY;
            playTone(ToneGenerator.TONE_PROP_PROMPT, 90);
        }
        if (!dragonDashing) return;
        if (gameTime < dragonMoveStart) return;

        float p = clamp01((gameTime - dragonSegmentStartAt)
                / Math.max(0.01f, dragonDashEnd - dragonSegmentStartAt));
        dragonCurrentX = lerp(dragonStartX, dragonTargetX, p);
        dragonCurrentY = lerp(dragonStartY, dragonTargetY, p);

        if (!dragonCorrected && target != null && p >= 0.43f
                && distance(dragonCurrentX, dragonCurrentY, target.x(), target.y()) > 6f) {
            dragonCorrected = true;
            dragonStartX = dragonCurrentX;
            dragonStartY = dragonCurrentY;
            dragonSegmentStartAt = gameTime;
            dragonDashEnd = gameTime + 0.92f;
            int[] corrected = projectAhead(target, 1);
            dragonAimX = clamp(corrected[0], 1, COLS - 2);
            dragonAimY = clamp(corrected[1], 1, mazeRows - 2);
            float[] exit = dragonExitBeyondMap(dragonStartX, dragonStartY, dragonAimX, dragonAimY);
            dragonTargetX = exit[0];
            dragonTargetY = exit[1];
        }

        if (!dragonHitApplied && player != null && player.alive
                && distance(dragonCurrentX, dragonCurrentY, player.x(), player.y()) < 0.82f) {
            dragonHitApplied = true;
            damagePlayer("被游龙冲撞");
        }

        if (gameTime >= dragonDashEnd) {
            dragonDashing = false;
            nextDragonDashAt = gameTime + 36f + random.nextFloat() * 28f;
            if (random.nextFloat() < 0.38f && !hasItem(ItemType.FAIRY_WAND)) {
                int[] pDrop = nearestPassable(Math.round(dragonAimX), Math.round(dragonAimY));
                if (!itemAt(pDrop[0], pDrop[1])) {
                    items.add(new Item(ItemType.FAIRY_WAND, pDrop[0], pDrop[1], gameTime + 14f));
                    showMessage("游龙留下仙女棒：拾取后全场NPC眩晕三秒", 2.1f);
                }
            }
        }
    }

    private float[] dragonExitBeyondMap(float sx, float sy, float aimX, float aimY) {
        float dx = aimX - sx;
        float dy = aimY - sy;
        if (Math.abs(dy) < 0.12f) dy = -0.12f;
        float tTop = (-2.8f - sy) / dy;
        float ex = sx + dx * tTop;
        float ey = -2.8f;
        if (tTop <= 1f || ex < -4f || ex > COLS + 3f) {
            float tLeft = (-3f - sx) / (Math.abs(dx) < 0.12f ? -0.12f : dx);
            float tRight = (COLS + 2f - sx) / (Math.abs(dx) < 0.12f ? 0.12f : dx);
            float tSide = dx < 0f ? tLeft : tRight;
            if (tSide > 1f) {
                ex = sx + dx * tSide;
                ey = sy + dy * tSide;
            }
        }
        return new float[]{ex, ey};
    }

    private void updateGalaxyEvents() {
        if (gameTime >= nextBlackHoleAt && !blackHoleRunning) {
            nextBlackHoleAt = gameTime + 19f + random.nextFloat() * 17f;
            if (random.nextFloat() < 0.55f && !meteor.active && reviveAt < 0f) {
                blackHoleRunning = true;
                blackHolePullStarted = false;
                blackHoleMode = random.nextBoolean() ? 0 : 1;
                blackHoleStart = gameTime;
                blackHoleActiveAt = gameTime + 1.05f;
                blackHoleEnd = gameTime + 3.05f;
                showMessage(blackHoleMode == 0 ? "黑洞预警：即将吸引玩家" : "黑洞预警：即将吸引全部敌人", 2f);
                playTone(ToneGenerator.TONE_CDMA_NETWORK_BUSY, 180);
            }
        }
        if (gameTime >= nextWormholeAt) {
            nextWormholeAt = gameTime + 22f + random.nextFloat() * 20f;
            if (random.nextFloat() < 0.30f && !hasItem(ItemType.WORMHOLE)) {
                spawnSafeItem(ItemType.WORMHOLE, 7f, 8);
            }
        }
        if (gameTime >= nextMeteorAt) {
            nextMeteorAt = gameTime + 13f + random.nextFloat() * 14f;
            if (random.nextFloat() < 0.52f && !blackHoleRunning && player != null && player.alive
                    && countExits(player.cellX, player.cellY) >= 2) {
                int[] predicted = projectAhead(player, 2);
                int[] target = nearestPassable(predicted[0], predicted[1]);
                meteor.x = target[0];
                meteor.y = target[1];
                meteor.impactAt = gameTime + 1.45f;
                meteor.vanishAt = meteor.impactAt + 0.65f;
                meteor.active = true;
                meteor.impacted = false;
                showMessage("陨石锁定预警！", 1.2f);
                vibrate(28);
            }
        }
        if (meteor.active && !meteor.impacted && gameTime >= meteor.impactAt) {
            meteor.impacted = true;
            playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 130);
            vibrate(55);
            if (player != null && player.alive && distance(player.x(), player.y(), meteor.x, meteor.y) < 1.55f) {
                damagePlayer("被陨石击中");
            }
        }
        if (meteor.active && gameTime >= meteor.vanishAt) meteor.active = false;
    }

    private boolean hasItem(ItemType type) {
        for (Item item : items) if (item.type == type) return true;
        return false;
    }

    private boolean hasArmedBigCracker() {
        for (Bomb b : activeBombs) if (b.big && !b.exploded) return true;
        return false;
    }

    private void spawnSafeItem(ItemType type, float lifetime, int minimumEnemyDistance) {
        List<int[]> candidates = new ArrayList<>();
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (isWall(x, y) || itemAt(x, y) || countExits(x, y) < (type == ItemType.BIG_CRACKER ? 2 : 1)) continue;
                if (nearestEnemyDistance(x, y) < minimumEnemyDistance) continue;
                if (player != null && distance(x, y, player.x(), player.y()) < 3.5f) continue;
                if (theme == Theme.CLOWN && inClownAttackZone(x, y)) continue;
                if (theme == Theme.OCEAN && type != ItemType.WORMHOLE && y > mazeRows * 0.72f) continue;
                candidates.add(new int[]{x, y});
            }
        }
        if (candidates.isEmpty()) {
            for (int y = 1; y < mazeRows - 1; y++) {
                for (int x = 1; x < COLS - 1; x++) {
                    if (!isWall(x, y) && !itemAt(x, y) && countExits(x, y) >= 2) candidates.add(new int[]{x, y});
                }
            }
        }
        if (!candidates.isEmpty()) {
            int[] p = candidates.get(random.nextInt(candidates.size()));
            items.add(new Item(type, p[0], p[1], gameTime + lifetime));
            switch (type) {
                case GIFT: showMessage("神秘礼盒出现了", 1.5f); break;
                case HEART: showMessage("限时爱心出现：拾取后十秒内可复活", 1.8f); break;
                case BIG_CRACKER: showMessage("大爆竹出现：拾取后有四秒撤离", 1.8f); break;
                case WORMHOLE: showMessage("小虫洞出现了", 1.5f); break;
                case GATE_CRACKER: showMessage("开门爆竹出现了", 1.5f); break;
                case FAIRY_WAND: showMessage("仙女棒出现了", 1.5f); break;
            }
        }
    }

    private boolean itemAt(int x, int y) {
        for (Item item : items) if (item.x == x && item.y == y) return true;
        return false;
    }

    private void updateItems() {
        Iterator<Item> iterator = items.iterator();
        while (iterator.hasNext()) {
            Item item = iterator.next();
            if (gameTime >= item.expires) iterator.remove();
        }
    }

    private void collectItemAt(Actor collector) {
        int x = collector.cellX, y = collector.cellY;
        Iterator<Item> iterator = items.iterator();
        while (iterator.hasNext()) {
            Item item = iterator.next();
            if (item.x != x || item.y != y) continue;
            iterator.remove();
            score += 100;
            playTone(ToneGenerator.TONE_PROP_ACK, 160);
            vibrate(30);
            switch (item.type) {
                case GIFT: activateRandomGift(); break;
                case HEART:
                    heartUntil = gameTime + 10f;
                    showMessage("爱心生效：十秒内死亡可无损复活", 2f);
                    break;
                case BIG_CRACKER:
                    activeBombs.add(new Bomb(x, y, gameTime + 4f, 3.25f, true));
                    showMessage("大爆竹已点燃：四秒后爆炸！", 2f);
                    break;
                case WORMHOLE:
                    teleportActorToSafety(collector);
                    break;
                case GATE_CRACKER:
                    armGateCracker(x, y);
                    break;
                case FAIRY_WAND:
                    for (Actor enemy : enemies) {
                        if (enemy != null && enemy.alive) {
                            enemy.stunnedUntil = Math.max(enemy.stunnedUntil, gameTime + 3f);
                        }
                    }
                    if (companion != null && companion.alive) {
                        companion.stunnedUntil = Math.max(companion.stunnedUntil, gameTime + 3f);
                    }
                    showMessage("仙女棒绽放：全场NPC眩晕三秒", 2f);
                    break;
            }
            break;
        }
    }

    private void activateRandomGift() {
        int choice = random.nextInt(companionSummoned ? 3 : 4);
        if (choice == 0) {
            shieldUntil = gameTime + 5f;
            showMessage("礼盒：五秒护盾", 1.8f);
        } else if (choice == 1) {
            speedUntil = gameTime + 5f;
            showMessage("礼盒：五秒内移动速度 +40%", 1.8f);
        } else if (choice == 2) {
            for (Actor e : enemies) if (e != null) e.stunnedUntil = Math.max(e.stunnedUntil, gameTime + 3f);
            showMessage("礼盒：所有敌人眩晕三秒", 1.8f);
        } else {
            activateCompanion("礼盒：高智能小跟班加入！");
        }
    }

    private void activateCompanion(String message) {
        if (companionSummoned) return;
        companionSummoned = true;
        resetExpertPlanner();
        int[] safe = theme == Theme.FACTORY ? safestFloorNearFactoryMaker() : safestFloorNearPlayer();
        companion.place(safe[0], safe[1]);
        companion.spawnX = safe[0];
        companion.spawnY = safe[1];
        companion.alive = true;
        companion.stunnedUntil = gameTime + 0.35f;
        companionBombAvailable = true;
        companionProtectedUntil = gameTime + 0.9f;
        collectPellet(companion);
        showMessage(message, 2.1f);
    }

    private int[] safestFloorNearFactoryMaker() {
        int cx = factoryMakerX >= 0 ? factoryMakerX : (player == null ? 1 : player.cellX);
        int cy = factoryMakerY >= 0 ? factoryMakerY : (player == null ? mazeRows - 2 : player.cellY);
        int bestX = cx;
        int bestY = cy;
        float best = -Float.MAX_VALUE;
        for (int y = Math.max(1, cy - 3); y <= Math.min(mazeRows - 2, cy + 3); y++) {
            for (int x = Math.max(1, cx - 3); x <= Math.min(COLS - 2, cx + 3); x++) {
                if (isWall(x, y)) continue;
                if (player != null && distance(x, y, player.x(), player.y()) < 0.8f) continue;
                float value = nearestEnemyDistance(x, y) * 4f - distance(cx, cy, x, y);
                if (countExits(x, y) >= 2) value += 5f;
                if (value > best) {
                    best = value;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

    private int[] safestFloorNearPlayer() {
        int px = player == null ? 1 : Math.round(player.x());
        int py = player == null ? mazeRows - 2 : Math.round(player.y());
        int bestX = px;
        int bestY = py;
        float best = -Float.MAX_VALUE;
        for (int y = Math.max(1, py - 6); y <= Math.min(mazeRows - 2, py + 6); y++) {
            for (int x = Math.max(1, px - 6); x <= Math.min(COLS - 2, px + 6); x++) {
                if (isWall(x, y)) continue;
                float value = nearestEnemyDistance(x, y) * 3f - distance(px, py, x, y);
                if (countExits(x, y) >= 2) value += 4f;
                if (value > best) {
                    best = value;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

    private void teleportActorToSafety(Actor actor) {
        if (actor == null || !actor.alive) return;
        int bestX = actor.cellX;
        int bestY = actor.cellY;
        float bestValue = -Float.MAX_VALUE;
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (isWall(x, y) || countExits(x, y) < 2) continue;
                float fromPlayer = distance(x, y, actor.x(), actor.y());
                if (fromPlayer < 8f) continue;
                float value = nearestEnemyDistance(x, y) * 5f + countExits(x, y) * 2f + random.nextFloat() * 2f;
                if (value > bestValue) {
                    bestValue = value;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        actor.place(bestX, bestY);
        if (actor == companion) companionProtectedUntil = Math.max(companionProtectedUntil, gameTime + 1.5f);
        else invincibleUntil = Math.max(invincibleUntil, gameTime + 1.5f);
        expertTargetX = expertTargetY = -1;
        showMessage("虫洞跃迁：已抵达远离敌人的位置", 2f);
    }

    private void updateCompanionEmergencyTurn() {
        Actor a = companion;
        if (a == null || a.progress <= 0.08f || a.progress >= 0.92f || a.dir == Dir.NONE
                || gameTime < expertEmergencyAt || gameTime < a.stunnedUntil
                || powerUntil > gameTime + 0.5f || gameTime - expertLastReverseAt < 0.45f) return;
        expertEmergencyAt = gameTime + 0.10f;
        int nx = stepX(a.cellX, a.cellY, a.dir), ny = stepY(a.cellX, a.cellY, a.dir);
        float front = nearestEnemyDistance(nx, ny), back = nearestEnemyDistance(a.cellX, a.cellY);
        if (front < 1.45f && back > front + 0.70f && back > 1.8f && canMove(nx, ny, a.dir.opposite())) {
            a.cellX = nx; a.cellY = ny; a.progress = 1f - a.progress;
            a.dir = a.dir.opposite(); a.wanted = a.dir;
            expertLastReverseAt = gameTime;
            expertTargetX = expertTargetY = -1;
            expertPlanLabel = "转身避险";
        }
    }

    private void updateCompanionBomb() {
        if (!companionBombAvailable || companion == null || !companion.alive
                || companion.forced || gameTime < companion.stunnedUntil) return;
        // 敌人已经处于可反击状态时不浪费唯一一次防身武器。
        if (powerUntil > gameTime + 0.4f) return;
        float triggerDistance = companion.kind == 10 ? 2.15f : 1.55f;
        float nearby = nearestEnemyDistance(Math.round(companion.x()), Math.round(companion.y()));
        if (nearby > triggerDistance) return;
        if (companion.kind == 10 && nearby > 1.55f
                && countExits(companion.cellX, companion.cellY) >= 3) return;
        int x = clamp(Math.round(companion.x()), 0, COLS - 1);
        int y = clamp(Math.round(companion.y()), 0, mazeRows - 1);
        if (isWall(x, y)) {
            x = companion.cellX;
            y = companion.cellY;
        }
        boolean canHit = false;
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || enemy.forced || gameTime < enemy.releaseAt
                    || enemy.stunnedUntil > gameTime + 0.6f) continue;
            if (theme == Theme.FACTORY ? factoryLaserHits(enemy.x(), enemy.y(), x, y, 3.45f)
                    : distance(enemy.x(), enemy.y(), x, y) < 2.85f) { canHit = true; break; }
        }
        if (!canHit) return;
        companionBombAvailable = false;
        companionProtectedUntil = Math.max(companionProtectedUntil, gameTime + 0.48f);
        if (theme == Theme.FACTORY) {
            activeBombs.add(new Bomb(x, y, gameTime + 0.14f, 3.45f, false));
            showMessage("机器人小跟班启动了本局唯一一次十字激光", 1.8f);
        } else {
            activeBombs.add(new Bomb(x, y, gameTime + 0.16f, 2.45f, false));
            showMessage(theme == Theme.SPRING ? "小跟班点燃了本局唯一一挂鞭炮" : "小跟班使用了本局唯一一枚炸弹", 1.6f);
        }
        playTone(ToneGenerator.TONE_PROP_PROMPT, 75);
    }

    private void dropNormalBomb() {
        if (screen != Screen.PLAYING || player == null || !player.alive || player.forced || bombCount <= 0) return;
        int x = Math.round(player.x());
        int y = Math.round(player.y());
        for (Bomb b : activeBombs) if (!b.exploded && b.x == x && b.y == y) return;
        bombCount--;
        if (theme == Theme.FACTORY) {
            activeBombs.add(new Bomb(x, y, gameTime + 0.85f, 3.45f, false));
            showMessage("十字激光已部署", 1f);
        } else {
            activeBombs.add(new Bomb(x, y, gameTime + 1.45f, 2.55f, false));
            showMessage(theme == Theme.SPRING ? "鞭炮已点燃" : "炸弹已投放", 1f);
        }
        playTone(ToneGenerator.TONE_PROP_PROMPT, 70);
    }

    private void updateBombs() {
        Iterator<Bomb> iterator = activeBombs.iterator();
        while (iterator.hasNext()) {
            Bomb bomb = iterator.next();
            if (!bomb.exploded && gameTime >= bomb.explodeAt) explodeBomb(bomb);
            if (bomb.exploded && gameTime >= bomb.vanishAt) iterator.remove();
        }
    }

    private void explodeBomb(Bomb bomb) {
        bomb.exploded = true;
        bomb.vanishAt = gameTime + 0.58f;
        boolean factoryLaser = theme == Theme.FACTORY && !bomb.big;
        playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, bomb.big ? 190 : 120);
        vibrate(bomb.big ? 85 : 50);
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive) continue;
            boolean hit = factoryLaser
                    ? factoryLaserHits(enemy.x(), enemy.y(), bomb.x, bomb.y, bomb.radius)
                    : distance(enemy.x(), enemy.y(), bomb.x, bomb.y) <= bomb.radius;
            if (hit) {
                enemy.stunnedUntil = Math.max(enemy.stunnedUntil, gameTime + (bomb.big ? 2.6f : 2.2f));
                enemy.revealed = true;
            }
        }
        if (factoryLaser) {
            showMessage("十字激光释放：仅命中横纵通道内的机器人", 1.4f);
        }
        if (bomb.big) {
            if (player != null && player.alive && distance(player.x(), player.y(), bomb.x, bomb.y) <= bomb.radius) {
                damagePlayer("被自己点燃的大爆竹炸到");
            }
            breakWallsAround(bomb.x, bomb.y, bomb.radius);
            showMessage("爆竹炸开墙壁，八秒后恢复", 2f);
        }
    }

    private void breakWallsAround(int cx, int cy, float radius) {
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (!walls[y][x] || distance(x, y, cx, cy) > radius) continue;
                if (brokenUntil[y][x] == 0f) brokenWallCount++;
                brokenUntil[y][x] = Math.max(brokenUntil[y][x], gameTime + 8f);
            }
        }
        wallCacheDirty = true;
    }

    private void updateBrokenWalls() {
        if (brokenWallCount == 0) return;
        boolean changed = false;
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (brokenUntil[y][x] <= 0f || gameTime < brokenUntil[y][x]) continue;
                if (cellOccupied(x, y)) {
                    brokenUntil[y][x] = gameTime + 0.65f;
                } else {
                    brokenUntil[y][x] = 0f;
                    brokenWallCount--;
                    changed = true;
                }
            }
        }
        if (changed) wallCacheDirty = true;
    }

    private boolean cellOccupied(int x, int y) {
        if (player != null && player.alive && distance(player.x(), player.y(), x, y) < 0.72f) return true;
        if (companion != null && companion.alive && distance(companion.x(), companion.y(), x, y) < 0.72f) return true;
        for (Actor e : enemies) if (e != null && e.alive && distance(e.x(), e.y(), x, y) < 0.72f) return true;
        return false;
    }

    private void updateGalaxyPull() {
        if (!blackHoleRunning) return;
        int[] core = nearestPassable(COLS / 2, mazeRows / 2);
        if (!blackHolePullStarted && gameTime >= blackHoleActiveAt) {
            blackHolePullStarted = true;
            if (blackHoleMode == 0) {
                if (player != null && player.alive) beginPull(player);
            } else {
                for (Actor enemy : enemies) if (enemy != null && enemy.alive) beginPull(enemy);
            }
        }
        if (blackHolePullStarted) {
            float p = clamp01((gameTime - blackHoleActiveAt) / Math.max(0.01f, blackHoleEnd - blackHoleActiveAt));
            float eased = p * p * (3f - 2f * p);
            if (blackHoleMode == 0) {
                if (player != null && player.forced) updatePullPosition(player, core[0], core[1], eased);
            } else {
                for (Actor enemy : enemies) if (enemy != null && enemy.forced) updatePullPosition(enemy, core[0], core[1], eased);
            }
        }
        if (gameTime >= blackHoleEnd) finishBlackHole(core[0], core[1]);
    }

    private void beginPull(Actor actor) {
        actor.pullStartX = actor.x();
        actor.pullStartY = actor.y();
        actor.forcedX = actor.pullStartX;
        actor.forcedY = actor.pullStartY;
        actor.forced = true;
    }

    private void updatePullPosition(Actor actor, float tx, float ty, float p) {
        actor.forcedX = lerp(actor.pullStartX, tx, p);
        actor.forcedY = lerp(actor.pullStartY, ty, p);
    }

    private void finishBlackHole(int coreX, int coreY) {
        if (blackHoleMode == 0) {
            if (player != null && player.forced) {
                int[] safe = safestFloorAround(coreX, coreY, 0);
                player.place(safe[0], safe[1]);
                invincibleUntil = Math.max(invincibleUntil, gameTime + 2f);
                globalScatterUntil = gameTime + 2.5f;
            }
        } else {
            int[][] offsets = {{-2, 0}, {2, 0}, {0, -2}, {0, 2}, {-2, -2}};
            for (int i = 0; i < enemies.length; i++) {
                Actor enemy = enemies[i];
                if (enemy != null && enemy.forced) {
                    int[] p = safestFloorAround(coreX + offsets[i][0], coreY + offsets[i][1], i);
                    enemy.place(p[0], p[1]);
                    enemy.stunnedUntil = gameTime + 2f;
                }
            }
        }
        blackHoleRunning = false;
        blackHolePullStarted = false;
        showMessage(blackHoleMode == 0 ? "黑洞释放：获得两秒安全时间" : "敌人被聚拢并眩晕两秒", 1.8f);
    }

    private int[] safestFloorAround(int tx, int ty, int salt) {
        int bestX = tx;
        int bestY = ty;
        float best = -Float.MAX_VALUE;
        for (int y = Math.max(1, ty - 5); y <= Math.min(mazeRows - 2, ty + 5); y++) {
            for (int x = Math.max(1, tx - 5); x <= Math.min(COLS - 2, tx + 5); x++) {
                if (isWall(x, y)) continue;
                float value = -distance(x, y, tx, ty) + countExits(x, y) * 0.5f + ((x + y + salt) % 3) * 0.1f;
                if (value > best) {
                    best = value;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

    private void checkCollisions() {
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || enemy.forced || gameTime < enemy.stunnedUntil
                    || gameTime < enemy.releaseAt
                    || isFactoryEnemyCharging(enemy)) continue;
            if (player != null && player.alive && !player.forced
                    && actorDistance(enemy, player) < 0.58f && gameTime >= safeUntil) {
                enemy.revealed = true;
                if (gameTime < powerUntil) eatEnemy(enemy);
                else damagePlayer("被敌人抓到");
            }
            if (companion != null && companion.alive && !companion.forced
                    && gameTime >= companionProtectedUntil
                    && actorDistance(enemy, companion) < 0.56f) {
                enemy.revealed = true;
                if (gameTime < powerUntil) eatEnemy(enemy);
                else damageCompanion("被敌人抓到");
            }
        }
    }

    private void eatEnemy(Actor enemy) {
        enemyEatChain++;
        score += 200 * (1 << Math.min(3, enemyEatChain - 1));
        enemy.place(enemy.spawnX, enemy.spawnY);
        enemy.stunnedUntil = gameTime + 1.25f;
        enemy.revealed = true;
        showMessage("反击成功！", 0.9f);
        playTone(ToneGenerator.TONE_PROP_ACK, 90);
    }

    private void damagePlayer(String reason) {
        if (player == null || !player.alive || gameTime < invincibleUntil || player.forced) return;
        if (gameTime < shieldUntil) {
            shieldUntil = 0f;
            invincibleUntil = gameTime + 0.8f;
            for (Actor enemy : enemies) {
                if (enemy != null && actorDistance(enemy, player) < 2.2f) enemy.stunnedUntil = gameTime + 1.2f;
            }
            showMessage("护盾抵消了一次伤害", 1.5f);
            playTone(ToneGenerator.TONE_PROP_NACK, 90);
            return;
        }

        playTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 220);
        vibrate(90);
        resetJoystick();
        player.alive = false;
        player.forced = false;
        if (gameTime < heartUntil) {
            heartUntil = 0f;
            heartRevive = true;
            reviveAt = gameTime + 2f;
            showMessage(reason + "；爱心将在两秒后复活你", 2f);
            return;
        }

        lives--;
        if (lives > 0) {
            heartRevive = false;
            reviveAt = gameTime + 1.35f;
            showMessage(reason + "；剩余生命 " + lives, 1.7f);
        } else if (companion != null && companion.alive) {
            reviveAt = -1f;
            companionOnly = true;
            breath = 5f;
            showMessage("玩家已阵亡，小跟班接管游戏", 2.5f);
        } else {
            finishGame(false);
        }
    }

    private void damageCompanion() {
        damageCompanion("小跟班被抓到了");
    }

    private void damageCompanion(String reason) {
        if (companion == null || !companion.alive || gameTime < companionProtectedUntil) return;
        if (companionOnly && gameTime < shieldUntil) {
            shieldUntil = 0f;
            companionProtectedUntil = gameTime + 0.9f;
            for (Actor enemy : enemies) {
                if (enemy != null && actorDistance(enemy, companion) < 2.2f) {
                    enemy.stunnedUntil = Math.max(enemy.stunnedUntil, gameTime + 1.2f);
                }
            }
            showMessage("护盾替AI抵消了一次伤害", 1.5f);
            return;
        }
        companion.alive = false;
        companion.forced = false;
        playTone(ToneGenerator.TONE_PROP_NACK, 110);
        if (companionOnly) {
            if (gameTime < heartUntil) {
                heartUntil = 0f;
                companionReviveAt = gameTime + 1.2f;
                showMessage(reason + "；爱心将复活AI", 1.8f);
                return;
            }
            finishGame(false);
            return;
        }
        showMessage(reason, 1.8f);
        if ((player == null || !player.alive) && reviveAt < 0f) finishGame(false);
    }

    private void updateCompanionRevival() {
        if (companionReviveAt < 0f || gameTime < companionReviveAt || companion == null) return;
        companionReviveAt = -1f;
        int[] respawn = chooseSafeRandomRespawn();
        companion.place(respawn[0], respawn[1]);
        companion.alive = true;
        companion.dir = Dir.NONE;
        companion.wanted = Dir.RIGHT;
        breath = 5f;
        companionProtectedUntil = gameTime + 2.4f;
        expertTargetX = expertTargetY = -1;
        expertTargetUntil = 0f;
        expertLastProgressAt = gameTime;
        safeUntil = Math.max(safeUntil, gameTime + 2.4f);
        globalScatterUntil = Math.max(globalScatterUntil, gameTime + 1.6f);
        showMessage("AI已在安全区域重新启动", 1.7f);
    }

    private void updateRevival() {
        if (reviveAt < 0f || gameTime < reviveAt) return;
        reviveAt = -1f;
        player.alive = true;
        int[] respawn = chooseSafeRandomRespawn();
        player.place(respawn[0], respawn[1]);
        invincibleUntil = gameTime + 2.4f;
        safeUntil = gameTime + 2.4f;
        if (heartRevive) {
            for (Actor enemy : enemies) {
                if (enemy != null && distance(enemy.x(), enemy.y(), player.x(), player.y()) <= 7f) {
                    enemy.stunnedUntil = gameTime + 2f;
                }
            }
            showMessage("爱心复活：周围敌人眩晕两秒", 2f);
        } else {
            globalScatterUntil = gameTime + 2f;
        }
        heartRevive = false;
    }

    private void finishGame(boolean won) {
        if (score > bestScore) {
            bestScore = score;
            prefs.edit().putInt("最高分_" + theme.ordinal(), score).apply();
        }
        resetJoystick();
        screen = won ? Screen.WIN : Screen.GAME_OVER;
        lastFrameNanos = 0L;
        playTone(won ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_NACK, won ? 280 : 220);
        invalidate();
    }

    private void showMessage(String text, float seconds) {
        banner = text;
        bannerUntil = gameTime + seconds;
    }

    private float actorDistance(Actor a, Actor b) {
        float dx = Math.abs(a.x() - b.x());
        float dy = Math.abs(a.y() - b.y());
        if (theme == Theme.CLASSIC || (theme == Theme.FACTORY
                && tunnelAxis == MazeGenerator.TUNNEL_HORIZONTAL)) dx = Math.min(dx, COLS - dx);
        if (theme == Theme.FACTORY && tunnelAxis == MazeGenerator.TUNNEL_VERTICAL) {
            dy = Math.min(dy, mazeRows - dy);
        }
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void drawHome(Canvas canvas) {
        drawMenuBackground(canvas);
        float cx = viewW * 0.5f, cy = viewH * 0.295f;
        float t = (System.nanoTime() % 100_000_000_000L) / 1_000_000_000f;
        drawLeftText(canvas, "星豆 · ARCADE", viewW * 0.08f, viewH * 0.075f,
                viewW * 0.030f, 0xFF83E8D2);
        drawLeftText(canvas, "一场属于你的迷宫冒险", viewW * 0.08f, viewH * 0.108f,
                viewW * 0.029f, 0xFFA8BED0);
        paint.setShader(heroShader);
        canvas.drawCircle(cx, cy, viewW * 0.48f, paint);
        paint.setShader(null);
        tmpRect.set(viewW * 0.085f, viewH * 0.155f, viewW * 0.915f, viewH * 0.43f);
        paint.setColor(0x60314762);
        canvas.drawRoundRect(tmpRect, viewW * 0.045f, viewW * 0.045f, paint);
        strokePaint.setColor(0x4473CBBE);
        strokePaint.setStrokeWidth(Math.max(1f, viewW * 0.002f));
        canvas.drawRoundRect(tmpRect, viewW * 0.045f, viewW * 0.045f, strokePaint);
        float unit = viewW * 0.072f;
        strokePaint.setStrokeWidth(viewW * 0.006f);
        strokePaint.setColor(0x855198AA);
        for (int side = -1; side <= 1; side += 2) {
            path.reset();
            path.moveTo(cx + side * unit * 3.8f, cy - unit * 1.5f);
            path.lineTo(cx + side * unit * 2.2f, cy - unit * 1.5f);
            path.lineTo(cx + side * unit * 2.2f, cy - unit * 0.7f);
            path.moveTo(cx + side * unit * 3.8f, cy - unit * 0.6f);
            path.lineTo(cx + side * unit * 3.0f, cy - unit * 0.6f);
            path.lineTo(cx + side * unit * 3.0f, cy + unit * 1.1f);
            path.lineTo(cx + side * unit * 1.9f, cy + unit * 1.1f);
            canvas.drawPath(path, strokePaint);
        }
        for (int i = 0; i < 7; i++) {
            float x = cx + (i - 3) * unit;
            paint.setColor(0xFFF0CB79);
            canvas.drawCircle(x, cy + unit * 1.9f, viewW * 0.006f, paint);
        }
        float bob = (float) Math.sin(t * 1.8f) * viewW * 0.008f;
        drawAppAvatar(canvas, cx, cy + bob - unit * 0.30f, viewW * 0.118f);
        drawPacShape(canvas, cx - unit * 3.8f, cy + unit * 1.9f, viewW * 0.035f, Dir.RIGHT, 0xFFFFD46A);
        drawGhost(canvas, cx + unit * 3.8f, cy + unit * 1.9f, viewW * 0.029f, 0xFF79E0CB, false, false);
        textPaint.setTypeface(FONT_TITLE);
        drawCenteredText(canvas, "星豆迷宫", cx, viewH * 0.495f, viewW * 0.094f, 0xFFFFF0CC);
        textPaint.setTypeface(FONT_NORMAL);
        drawCenteredText(canvas, "穿过未知，收集每一颗星光", cx, viewH * 0.54f,
                viewW * 0.031f, 0xFFBACDDD);
        drawRoundedButton(canvas, startButton, "开始冒险   ›", 0xFFFFD178, 0xFF17243A);
        drawRoundedButton(canvas, guideButton, "玩法与小跟班", 0x55395170, 0xFFE2EDF6);
        drawCenteredText(canvas, "六种主题  /  三档难度  /  随机迷宫", cx, viewH * 0.815f,
                viewW * 0.027f, 0xFF9FBDCF);
        drawCenteredText(canvas, "离线畅玩 · 智能伙伴同行", cx, viewH * 0.855f,
                viewW * 0.029f, 0xFF7FE0C6);
        drawCenteredText(canvas, "v1.20.0", cx, viewH * 0.926f,
                viewW * 0.023f, 0xFF7291AA);
    }

    private void drawAppAvatar(Canvas canvas, float cx, float cy, float radius) {
        paint.setColor(0x55FFFFFF);
        canvas.drawCircle(cx, cy, radius * 1.14f, paint);
        if (appAvatar == null) {
            drawPacShape(canvas, cx, cy, radius, Dir.RIGHT, 0xFFFFD84A);
            return;
        }
        int save = canvas.save();
        path.reset();
        path.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.clipPath(path);
        appAvatar.setBounds(Math.round(cx - radius), Math.round(cy - radius),
                Math.round(cx + radius), Math.round(cy + radius));
        appAvatar.draw(canvas);
        canvas.restoreToCount(save);
        strokePaint.setColor(0xCCFFFFFF);
        strokePaint.setStrokeWidth(Math.max(2f, radius * 0.055f));
        canvas.drawCircle(cx, cy, radius, strokePaint);
    }

    private void drawMenuBackground(Canvas canvas) {
        paint.setShader(menuShader);
        canvas.drawRect(0, 0, viewW, viewH, paint);
        paint.setShader(null);
        float t = (System.nanoTime() % 1_000_000_000_000L) / 1_000_000_000f;
        for (int i = 0; i < starX.length; i++) {
            int alpha = 95 + (int) (95 * (0.5 + 0.5 * Math.sin(t * 1.4 + i)));
            paint.setColor(Color.argb(alpha, 235, 242, 255));
            canvas.drawCircle(starX[i] * viewW, starY[i] * viewH,
                    starSize[i] * viewW / 360f, paint);
        }
    }

    private void drawMapSelect(Canvas canvas) {
        drawMenuBackground(canvas);
        paint.setShader(new RadialGradient(viewW * 0.10f, viewH * 0.28f, viewW * 0.62f,
                0x554E69D9, 0x002B1762, Shader.TileMode.CLAMP));
        canvas.drawCircle(viewW * 0.10f, viewH * 0.28f, viewW * 0.62f, paint);
        paint.setShader(new RadialGradient(viewW * 0.92f, viewH * 0.56f, viewW * 0.56f,
                0x4437D6C5, 0x00164A6D, Shader.TileMode.CLAMP));
        canvas.drawCircle(viewW * 0.92f, viewH * 0.56f, viewW * 0.56f, paint);
        paint.setShader(null);

        drawBackButton(canvas, "返回");
        drawCenteredText(canvas, "主题档案 · 06", viewW * 0.5f, viewH * 0.044f,
                viewW * 0.020f, 0xFFD1D8F5);
        textPaint.setTypeface(FONT_BOLD);
        drawCenteredText(canvas, "选择主题地图", viewW * 0.5f, viewH * 0.086f,
                viewW * 0.051f, Color.WHITE);
        textPaint.setTypeface(FONT_NORMAL);
        drawCenteredText(canvas, "完整地图会等比例呈现，每局地形与视觉尺寸都会轻微变化", viewW * 0.5f,
                viewH * 0.119f, viewW * 0.0205f, 0xFFE0E4F8);
        Theme[] themes = Theme.values();
        for (int i = 0; i < themes.length; i++) drawMapCard(canvas, mapCards[i], themes[i], i);
        drawGameSettings(canvas);
    }

    private void drawGameSettings(Canvas canvas) {
        float top = viewH * 0.615f;
        float bottom = viewH * 0.862f;
        tmpRect.set(viewW * 0.055f, top + viewH * 0.004f, viewW * 0.945f, bottom + viewH * 0.004f);
        paint.setColor(0x3D000000);
        canvas.drawRoundRect(tmpRect, viewW * 0.027f, viewW * 0.027f, paint);
        tmpRect.set(viewW * 0.055f, top, viewW * 0.945f, bottom);
        paint.setShader(new LinearGradient(tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom,
                new int[]{0xD94B3B79, 0xD92B456F, 0xD9235271},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(tmpRect, viewW * 0.027f, viewW * 0.027f, paint);
        paint.setShader(null);
        strokePaint.setColor(0x557D9AD0);
        strokePaint.setStrokeWidth(viewW * 0.0018f);
        canvas.drawRoundRect(tmpRect, viewW * 0.027f, viewW * 0.027f, strokePaint);

        drawLeftText(canvas, "本局设置", viewW * 0.082f, top + viewH * 0.028f,
                viewW * 0.027f, Color.WHITE);
        drawLeftText(canvas, "生命 1—5", viewW * 0.082f, top + viewH * 0.048f,
                viewW * 0.0175f, 0xFFD2D9F0);
        RectF lifeSurface = new RectF(viewW * 0.072f, lifeMinusButton.top - viewH * 0.002f,
                viewW * 0.370f, lifeMinusButton.bottom + viewH * 0.002f);
        paint.setColor(0x55404E78);
        canvas.drawRoundRect(lifeSurface, viewW * 0.018f, viewW * 0.018f, paint);
        drawRoundedButton(canvas, lifeMinusButton, "−", 0x5B505C86, Color.WHITE);
        drawCenteredText(canvas, String.valueOf(configuredLives), viewW * 0.220f,
                lifeSurface.centerY() + viewW * 0.010f, viewW * 0.038f, 0xFFFFD468);
        drawRoundedButton(canvas, lifePlusButton, "+", 0x5B505C86, Color.WHITE);

        drawLeftText(canvas, "难度", viewW * 0.445f, top + viewH * 0.048f,
                viewW * 0.0175f, 0xFFD2D9F0);
        int diffColor = difficulty == Difficulty.EASY ? 0xFF83E3B4
                : difficulty == Difficulty.HARD ? 0xFFFF91AA : 0xFF8BB3FF;
        paint.setColor(difficulty == Difficulty.EASY ? 0x5B52A98A
                : difficulty == Difficulty.HARD ? 0x5B6A395B : 0x5B5067A1);
        canvas.drawRoundRect(difficultyButton, viewW * 0.018f, viewW * 0.018f, paint);
        strokePaint.setColor(Color.argb(100, Color.red(diffColor), Color.green(diffColor), Color.blue(diffColor)));
        strokePaint.setStrokeWidth(viewW * 0.0018f);
        canvas.drawRoundRect(difficultyButton, viewW * 0.018f, viewW * 0.018f, strokePaint);
        String enemyCount = difficulty == Difficulty.EASY ? "三名敌人"
                : difficulty == Difficulty.HARD ? "五名敌人" : "四名敌人";
        drawLeftText(canvas, difficulty.label + " · " + enemyCount,
                difficultyButton.left + viewW * 0.020f,
                difficultyButton.centerY() - viewH * 0.002f,
                viewW * 0.0235f, Color.WHITE);
        drawLeftText(canvas, difficulty.description,
                difficultyButton.left + viewW * 0.020f,
                difficultyButton.centerY() + viewH * 0.015f,
                viewW * 0.0155f, 0xFFD0D6EE);
        drawCenteredText(canvas, "›", difficultyButton.right - viewW * 0.028f,
                difficultyButton.centerY() + viewW * 0.010f, viewW * 0.034f, diffColor);

        drawLeftText(canvas, "玩家形象", viewW * 0.082f, viewH * 0.758f,
                viewW * 0.021f, 0xFFE7E9F8);
        String[] styleNames = {"经典吃豆人", "黄色小鸟"};
        for (int i = 0; i < playerStyleButtons.length; i++) {
            RectF r = playerStyleButtons[i];
            boolean selected = playerStyle == i;
            paint.setColor(selected ? 0x705F75B4 : 0x45404D73);
            canvas.drawRoundRect(r, viewW * 0.017f, viewW * 0.017f, paint);
            strokePaint.setColor(selected ? 0xFFFFD468 : 0x557E88AE);
            strokePaint.setStrokeWidth(viewW * (selected ? 0.003f : 0.0016f));
            canvas.drawRoundRect(r, viewW * 0.017f, viewW * 0.017f, strokePaint);
            float ix = r.centerX();
            float iy = r.centerY() - viewH * 0.008f;
            drawPlayerStylePreview(canvas, i, ix, iy, viewW * 0.026f);
            drawCenteredFittedText(canvas, styleNames[i], ix, r.bottom - viewH * 0.008f,
                    viewW * 0.015f, selected ? 0xFFFFE49A : 0xFFE0E3F3, r.width() * 0.88f);
        }

    }


    private void drawExpertAiCompanion(Canvas canvas, float x, float y, float r, Dir dir) {
        float angle = dir == Dir.UP ? -90f : dir == Dir.DOWN ? 90f : dir == Dir.LEFT ? 180f : 0f;
        canvas.save();
        canvas.rotate(angle, x, y);
        paint.setColor(0x445BF5D2);
        canvas.drawCircle(x, y, r * 1.42f, paint);
        paint.setShader(new RadialGradient(x - r * 0.35f, y - r * 0.35f, r * 1.7f,
                0xFF9FFFE7, 0xFF238E9C, Shader.TileMode.CLAMP));
        tmpRect.set(x - r, y - r, x + r, y + r);
        canvas.drawArc(tmpRect, 35f, 290f, true, paint);
        paint.setShader(null);
        strokePaint.setColor(0xFF123C51);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.12f));
        canvas.drawArc(tmpRect, 35f, 290f, true, strokePaint);
        paint.setColor(0xFF1B3144);
        tmpRect.set(x - r * 0.28f, y - r * 0.55f, x + r * 0.55f, y - r * 0.08f);
        canvas.drawRoundRect(tmpRect, r * 0.18f, r * 0.18f, paint);
        paint.setColor(0xFFFFD65A);
        canvas.drawCircle(x + r * 0.30f, y - r * 0.31f, r * 0.10f, paint);
        strokePaint.setColor(0xFF7CF5E0);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.075f));
        canvas.drawLine(x - r * 0.18f, y - r * 0.76f, x - r * 0.18f, y - r * 1.10f, strokePaint);
        paint.setColor(0xFFFF6F75);
        canvas.drawCircle(x - r * 0.18f, y - r * 1.12f, r * 0.11f, paint);
        paint.setColor(0xFF173044);
        canvas.drawCircle(x - r * 0.50f, y + r * 0.48f, r * 0.16f, paint);
        canvas.restore();
    }

    private void drawSpectatorPanel(Canvas canvas) {
        float cy = controlTop + (viewH - controlTop) * 0.46f;
        if (theme == Theme.FACTORY) drawRobotCompanion(canvas, viewW * 0.22f, cy, viewW * 0.055f, Dir.RIGHT);
        else drawExpertAiCompanion(canvas, viewW * 0.22f, cy, viewW * 0.055f, Dir.RIGHT);
        drawLeftText(canvas, "小跟班正在接管", viewW * 0.33f, cy - viewH * 0.008f,
                viewW * 0.038f, 0xFFB9FFF0);
        drawLeftText(canvas, expertPlanLabel, viewW * 0.33f, cy + viewH * 0.025f,
                viewW * 0.030f, 0xFFD3D9ED);
        drawCenteredText(canvas, companionBombAvailable ? "防身武器可用 · 自主寻找出口" : "自主收集与避险",
                viewW * 0.5f, controlTop + (viewH - controlTop) * 0.79f,
                viewW * 0.025f, 0xFFFFD779);
    }

    private void drawMapCard(Canvas canvas, RectF r, Theme t, int index) {
        float radius = viewW * 0.025f;
        tmpRect.set(r.left, r.top + viewH * 0.004f, r.right, r.bottom + viewH * 0.004f);
        paint.setColor(0x55000000);
        canvas.drawRoundRect(tmpRect, radius, radius, paint);

        int topColor = mixColor(t.dark, Color.WHITE, 0.18f);
        int bottomColor = mixColor(t.dark, 0xFF10213C, 0.24f);
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{topColor, t.dark, bottomColor},
                new float[]{0f, 0.54f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);

        tmpRect.set(r.left, r.top + radius, r.left + viewW * 0.007f, r.bottom - radius);
        paint.setColor(Color.argb(225, Color.red(t.accent), Color.green(t.accent), Color.blue(t.accent)));
        canvas.drawRoundRect(tmpRect, viewW * 0.004f, viewW * 0.004f, paint);
        strokePaint.setStrokeWidth(viewW * 0.0017f);
        strokePaint.setColor(Color.argb(100, Color.red(t.accent), Color.green(t.accent), Color.blue(t.accent)));
        canvas.drawRoundRect(r, radius, radius, strokePaint);

        String[] tags = {"狂欢", "经典", "潮汐", "新春", "深空", "工业"};
        RectF tag = new RectF(r.left + viewW * 0.020f, r.top + viewH * 0.010f,
                r.left + viewW * 0.103f, r.top + viewH * 0.034f);
        paint.setColor(Color.argb(48, Color.red(t.accent), Color.green(t.accent), Color.blue(t.accent)));
        canvas.drawRoundRect(tag, tag.height() * 0.5f, tag.height() * 0.5f, paint);
        drawCenteredText(canvas, tags[index], tag.centerX(), tag.centerY() + viewW * 0.005f,
                viewW * 0.015f, t.accent);

        float iconX = r.centerX();
        float iconY = r.top + r.height() * 0.31f;
        float size = Math.min(r.width(), r.height()) * 0.18f;
        paint.setShader(new RadialGradient(iconX, iconY, size * 2.1f,
                Color.argb(70, Color.red(t.accent), Color.green(t.accent), Color.blue(t.accent)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawCircle(iconX, iconY, size * 2.1f, paint);
        paint.setShader(null);
        paint.setColor(0x2FFFFFFF);
        canvas.drawCircle(iconX, iconY, size * 1.38f, paint);
        switch (index) {
            case 0: drawMiniClown(canvas, iconX, iconY, size * 0.92f); break;
            case 1: drawPacShape(canvas, iconX, iconY, size * 0.78f, Dir.RIGHT, 0xFFFFD84A); break;
            case 2: drawFish(canvas, iconX, iconY, size * 1.10f, 0xFF56D8FF, false, 0, Dir.RIGHT); break;
            case 3: drawFirecracker(canvas, iconX, iconY, size * 0.84f, true); break;
            case 4: drawPlanet(canvas, iconX, iconY, size * 0.80f, 0xFFA999FF); break;
            case 5: drawFactoryIcon(canvas, iconX, iconY, size * 0.92f); break;
        }

        textPaint.setTypeface(FONT_BOLD);
        drawCenteredText(canvas, t.title, r.centerX(), r.top + r.height() * 0.61f,
                viewW * 0.030f, Color.WHITE);
        textPaint.setTypeface(FONT_NORMAL);
        drawCenteredFittedText(canvas, t.subtitle, r.centerX(), r.top + r.height() * 0.76f,
                viewW * 0.0168f, 0xFFF0F2FF, r.width() * 0.88f);
        int best = prefs.getInt("最高分_" + index, 0);
        RectF scoreChip = new RectF(r.centerX() - r.width() * 0.27f,
                r.top + r.height() * 0.835f,
                r.centerX() + r.width() * 0.27f,
                r.top + r.height() * 0.965f);
        paint.setColor(0x2A101526);
        canvas.drawRoundRect(scoreChip, scoreChip.height() * 0.5f, scoreChip.height() * 0.5f, paint);
        drawCenteredText(canvas, best > 0 ? "最高分 " + best : "轻触开始",
                scoreChip.centerX(), scoreChip.centerY() + viewW * 0.0048f,
                viewW * 0.0155f, t.accent);
    }


    private int[] chooseFactoryManufacturingPoint() {
        List<int[]> candidates = new ArrayList<>();
        for (int y = 3; y < mazeRows - 3; y++) {
            for (int x = 2; x < COLS - 2; x++) {
                if (isWall(x, y) || countExits(x, y) < 2 || occupiedByActor(x, y)) continue;
                if (factoryMakerX >= 0 && factoryConveyors[y][x] != 0) continue;
                if (distance(x, y, FACTORY_DOOR_X, FACTORY_DOOR_Y) < 5f) continue;
                if (x == factoryTurretX && y == factoryTurretY) continue;
                if (factoryMakerX >= 0 && distance(x, y, factoryMakerX, factoryMakerY) < 6f) continue;
                if (player != null && distance(x, y, player.x(), player.y()) < 3.2f) continue;
                if (powerPellets[y][x]) continue;
                // 刷新阶段优先选择仍有普通齿轮豆的位置，便于无损转移制造点。
                if (factoryMakerX >= 0 && !pellets[y][x]) continue;
                candidates.add(new int[]{x, y});
            }
        }
        if (candidates.isEmpty()) {
            for (int y = 2; y < mazeRows - 2; y++) {
                for (int x = 2; x < COLS - 2; x++) {
                    if (!isWall(x, y) && countExits(x, y) >= 2 && !powerPellets[y][x]) {
                        candidates.add(new int[]{x, y});
                    }
                }
            }
        }
        if (candidates.isEmpty()) return nearestPassable(3, mazeRows - 5);
        Collections.shuffle(candidates, random);
        int pool = Math.min(10, candidates.size());
        return candidates.get(random.nextInt(pool));
    }

    private void configureFactoryConveyors() {
        for (byte[] row : factoryConveyors) Arrays.fill(row, (byte) 0);
        int wanted = 3 + random.nextInt(2);
        int placed = 0;
        for (int attempt = 0; attempt < 220 && placed < wanted; attempt++) {
            boolean horizontal = random.nextBoolean();
            int length = 3 + random.nextInt(2);
            int sx = 2 + random.nextInt(COLS - 4);
            int sy = 2 + random.nextInt(mazeRows - 4);
            if (horizontal && sx + length >= COLS - 1) sx = COLS - length - 2;
            if (!horizontal && sy + length >= mazeRows - 1) sy = mazeRows - length - 2;
            boolean ok = true;
            for (int i = 0; i < length; i++) {
                int x = sx + (horizontal ? i : 0);
                int y = sy + (horizontal ? 0 : i);
                if (isWall(x, y) || factoryConveyors[y][x] != 0
                        || x == factoryMakerX && y == factoryMakerY
                        || distance(x, y, FACTORY_DOOR_X, FACTORY_DOOR_Y) < 2.2f) {
                    ok = false;
                    break;
                }
                if (i > 0) {
                    Dir back = horizontal ? Dir.LEFT : Dir.UP;
                    if (!canMove(x, y, back)) { ok = false; break; }
                }
            }
            if (!ok) continue;
            for (int i = 0; i < length; i++) {
                int x = sx + (horizontal ? i : 0);
                int y = sy + (horizontal ? 0 : i);
                factoryConveyors[y][x] = (byte) (horizontal ? 1 : 2);
            }
            placed++;
        }
    }

    private float factoryConveyorMultiplier(Actor actor) {
        if (theme != Theme.FACTORY || actor == null) return 1f;
        int x = clamp(Math.round(actor.x()), 0, COLS - 1);
        int y = clamp(Math.round(actor.y()), 0, mazeRows - 1);
        return factoryConveyors[y][x] == 0 ? 1f : 2.5f;
    }

    private void configureFactoryChargeTargets() {
        Arrays.fill(factoryChargeTargetX, FACTORY_DOOR_X);
        Arrays.fill(factoryChargeTargetY, FACTORY_DOOR_Y + 1);
        List<int[]> candidates = new ArrayList<>();
        for (int radius = 0; radius <= 5; radius++) {
            for (int y = Math.max(1, FACTORY_DOOR_Y - radius); y <= Math.min(mazeRows - 2, FACTORY_DOOR_Y + radius); y++) {
                for (int x = Math.max(1, FACTORY_DOOR_X - radius); x <= Math.min(COLS - 2, FACTORY_DOOR_X + radius); x++) {
                    if (Math.abs(x - FACTORY_DOOR_X) + Math.abs(y - FACTORY_DOOR_Y) != radius) continue;
                    if (isWall(x, y) || x == factoryMakerX && y == factoryMakerY) continue;
                    candidates.add(new int[]{x, y});
                }
            }
            if (candidates.size() >= 5) break;
        }
        if (candidates.isEmpty()) candidates.add(nearestPassable(FACTORY_DOOR_X, FACTORY_DOOR_Y + 2));
        factoryBaseX = candidates.get(0)[0];
        factoryBaseY = candidates.get(0)[1];
        for (int i = 0; i < factoryChargeTargetX.length; i++) {
            int[] p = candidates.get(Math.min(i, candidates.size() - 1));
            factoryChargeTargetX[i] = p[0];
            factoryChargeTargetY[i] = p[1];
        }
    }

    private void updateFactoryManufacturing(float dt) {
        if (companionSummoned || player == null || !player.alive || factoryMakerX < 0) {
            factoryMakerProgress = companionSummoned ? 3f : 0f;
            return;
        }
        boolean standing = distance(player.x(), player.y(), factoryMakerX, factoryMakerY) <= 0.43f;
        if (standing) {
            factoryMakerProgress = Math.min(3f, factoryMakerProgress + dt);
            if (factoryMakerProgress >= 3f) {
                activateCompanion("制造完成：机器人小跟班已启动！");
                factoryMakerProgress = 3f;
                score += 150;
                playTone(ToneGenerator.TONE_PROP_ACK, 160);
            }
        } else {
            factoryMakerProgress = 0f;
        }
    }

    private void configureReturnSpeedForEnemy(Actor enemy) {
        if (enemy == null) return;
        buildDistanceMap(factoryChargeTargetX[enemy.kind], factoryChargeTargetY[enemy.kind], bfsDistance);
        int dist = bfsDistance[clamp(enemy.cellY, 0, mazeRows - 1)][clamp(enemy.cellX, 0, COLS - 1)];
        if (dist < 0) dist = 8;
        factoryReturnSpeed[enemy.kind] = clampFloat(dist / 6.0f, 2.8f, 6.6f);
    }

    private void beginFactoryChargeReturn() {
        factoryReturningToCharge = true;
        factoryCharging = false;
        factoryReturnDeadline = Float.MAX_VALUE;
        Arrays.fill(factoryAtBase, false);
        Arrays.fill(factoryChargeCompleted, false);
        Arrays.fill(factoryChargeUntilByEnemy, 0f);
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive) continue;
            configureReturnSpeedForEnemy(enemy);
            enemy.revealed = true;
        }
        showMessage("困难事件：全部机器人同时返厂充电", 2.4f);
        playTone(ToneGenerator.TONE_PROP_PROMPT, 120);
    }

    private void updateFactoryChargeCycle() {
        if (difficulty != Difficulty.HARD) return;
        if (!factoryReturningToCharge && !factoryCharging && gameTime >= factoryNextChargeAt) {
            beginFactoryChargeReturn();
        }
        if (!factoryReturningToCharge) return;

        boolean anyCharging = false;
        float latestChargeEnd = 0f;
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || gameTime < enemy.releaseAt) continue;
            int k = enemy.kind;
            if (factoryChargeCompleted[k]) continue;
            if (!factoryAtBase[k] && distance(enemy.x(), enemy.y(),
                    factoryChargeTargetX[k], factoryChargeTargetY[k]) <= 0.34f) {
                // 机器人必须真实走到充电位；抵达时间允许有几秒偏差，各自立即开始充电。
                enemy.place(factoryChargeTargetX[k], factoryChargeTargetY[k]);
                factoryAtBase[k] = true;
                factoryChargeUntilByEnemy[k] = gameTime + 3f;
                showMessage("机器人抵达基地并开始充电", 1.2f);
            }
            if (factoryAtBase[k]) {
                if (gameTime < factoryChargeUntilByEnemy[k]) {
                    anyCharging = true;
                    latestChargeEnd = Math.max(latestChargeEnd, factoryChargeUntilByEnemy[k]);
                } else {
                    factoryAtBase[k] = false;
                    factoryChargeCompleted[k] = true;
                    enemy.wanted = chooseAnyDirection(enemy, Dir.NONE);
                }
            }
        }

        boolean allCompleted = true;
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || gameTime < enemy.releaseAt) continue;
            allCompleted &= factoryChargeCompleted[enemy.kind];
        }
        factoryCharging = anyCharging;
        factoryChargeUntil = latestChargeEnd;
        if (allCompleted) {
            factoryReturningToCharge = false;
            factoryCharging = false;
            Arrays.fill(factoryAtBase, false);
            factoryNextChargeAt = gameTime + 40f;
            globalScatterUntil = Math.max(globalScatterUntil, gameTime + 1.2f);
            showMessage("本轮充电完成：机器人已陆续重新出动", 1.8f);
        }
    }

    private boolean isFactoryEnemyCharging(Actor enemy) {
        return theme == Theme.FACTORY && enemy != null && enemy.kind >= 0
                && enemy.kind < factoryAtBase.length && factoryAtBase[enemy.kind]
                && gameTime < factoryChargeUntilByEnemy[enemy.kind];
    }

    private Dir chooseFactoryReturnDirection(Actor enemy) {
        int tx = factoryChargeTargetX[enemy.kind];
        int ty = factoryChargeTargetY[enemy.kind];
        buildDistanceMap(tx, ty, bfsDistance);
        List<Dir> choices = availableDirections(enemy.cellX, enemy.cellY, enemy.dir.opposite());
        if (choices.isEmpty()) choices = availableDirections(enemy.cellX, enemy.cellY, Dir.NONE);
        Dir best = Dir.NONE;
        int bestDist = Integer.MAX_VALUE;
        for (Dir d : choices) {
            int nx = stepX(enemy.cellX, enemy.cellY, d);
            int ny = stepY(enemy.cellX, enemy.cellY, d);
            int value = safeDistanceValue(bfsDistance[ny][nx]);
            if (value < bestDist) {
                bestDist = value;
                best = d;
            }
        }
        return best;
    }

    private void updateFactoryArmAttack() {
        if (factoryArmPullActive) {
            float p = clamp01((gameTime - factoryArmPullStartAt) /
                    Math.max(0.01f, factoryArmPullEndAt - factoryArmPullStartAt));
            float eased = p * p * (3f - 2f * p);
            player.forcedX = lerp(factoryArmStartX, factoryArmTargetX, eased);
            player.forcedY = lerp(factoryArmStartY, factoryArmTargetY, eased);
            if (p >= 1f) {
                player.forced = false;
                player.place(factoryArmTargetX, factoryArmTargetY);
                factoryArmPullActive = false;
                factoryArmEnemyKind = -1;
                safeUntil = Math.max(safeUntil, gameTime + 0.12f);
            }
            return;
        }
        if (difficulty != Difficulty.HARD || player == null || !player.alive || player.forced
                || factoryReturningToCharge || factoryCharging || gameTime < powerUntil
                || gameTime < safeUntil || gameTime < nextFactoryArmAt) return;
        nextFactoryArmAt = gameTime + 5f + random.nextFloat() * 8f;
        if (random.nextFloat() > 0.20f) return;

        int px = clamp(Math.round(player.x()), 0, COLS - 1);
        int py = clamp(Math.round(player.y()), 0, mazeRows - 1);
        List<Actor> candidates = new ArrayList<>();
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive || gameTime < enemy.releaseAt
                    || gameTime < enemy.stunnedUntil) continue;
            int ex = clamp(Math.round(enemy.x()), 0, COLS - 1);
            int ey = clamp(Math.round(enemy.y()), 0, mazeRows - 1);
            int steps;
            if (ey == py) {
                steps = Math.abs(ex - px);
                if (steps >= 2 && steps <= 10
                        && clearLaserLine(px, py, Integer.signum(ex - px), 0, steps)) candidates.add(enemy);
            } else if (ex == px) {
                steps = Math.abs(ey - py);
                if (steps >= 2 && steps <= 10
                        && clearLaserLine(px, py, 0, Integer.signum(ey - py), steps)) candidates.add(enemy);
            }
        }
        if (candidates.isEmpty()) return;
        Actor attacker = candidates.get(random.nextInt(candidates.size()));
        int ex = clamp(Math.round(attacker.x()), 0, COLS - 1);
        int ey = clamp(Math.round(attacker.y()), 0, mazeRows - 1);
        Dir pullDir = ex == px ? (ey < py ? Dir.UP : Dir.DOWN)
                : (ex < px ? Dir.LEFT : Dir.RIGHT);
        int tx = px;
        int ty = py;
        for (int step = 0; step < 2 && canMove(tx, ty, pullDir); step++) {
            int oldX = tx;
            int oldY = ty;
            tx = stepX(oldX, oldY, pullDir);
            ty = stepY(oldX, oldY, pullDir);
        }
        if (tx == px && ty == py) return;
        factoryArmEnemyKind = attacker.kind;
        factoryArmPullActive = true;
        factoryArmPullStartAt = gameTime;
        factoryArmPullEndAt = gameTime + 0.46f;
        factoryArmStartX = player.x();
        factoryArmStartY = player.y();
        factoryArmTargetX = tx;
        factoryArmTargetY = ty;
        player.pullStartX = factoryArmStartX;
        player.pullStartY = factoryArmStartY;
        player.forcedX = factoryArmStartX;
        player.forcedY = factoryArmStartY;
        player.forced = true;
        showMessage("机械臂直线锁定：玩家被拉近两格！", 1.4f);
        vibrate(40);
    }

    private void configureFactoryTurret() {
        List<int[]> candidates = new ArrayList<>();
        for (int y = 2; y < mazeRows - 2; y++) {
            for (int x = 2; x < COLS - 2; x++) {
                if (!isWall(x, y)) continue;
                int open = 0;
                for (Dir d : CARDINALS) {
                    int nx = x + d.dx;
                    int ny = y + d.dy;
                    if (nx >= 0 && nx < COLS && ny >= 0 && ny < mazeRows && !isWall(nx, ny)) open++;
                }
                if (open == 0) continue;
                if (distance(x, y, FACTORY_DOOR_X, FACTORY_DOOR_Y) < 4.5f) continue;
                if (factoryMakerX >= 0 && distance(x, y, factoryMakerX, factoryMakerY) < 3f) continue;
                if (player != null && distance(x, y, player.x(), player.y()) < 5f) continue;
                candidates.add(new int[]{x, y});
            }
        }
        if (candidates.isEmpty()) {
            factoryTurretX = 1;
            factoryTurretY = 1;
        } else {
            int[] p = candidates.get(random.nextInt(candidates.size()));
            factoryTurretX = p[0];
            factoryTurretY = p[1];
        }
    }

    private void updateFactoryLaserTurret() {
        if (factoryTurretX < 0 || factoryTurretY < 0) return;
        Actor target = player != null && player.alive ? player
                : companion != null && companion.alive ? companion : null;
        if (!factoryTurretCharging) {
            if (target != null && gameTime >= factoryTurretNextAt) {
                factoryTurretCharging = true;
                factoryTurretLocked = false;
                factoryTurretHitApplied = false;
                factoryTurretChargeStart = gameTime;
                factoryTurretFireAt = gameTime + 1.85f;
                factoryTurretFireUntil = factoryTurretFireAt + 0.38f;
                factoryTurretAimX = target.x();
                factoryTurretAimY = target.y();
            }
            return;
        }

        target = player != null && player.alive ? player
                : companion != null && companion.alive ? companion : null;
        if (!factoryTurretLocked && gameTime < factoryTurretFireAt - 0.45f && target != null) {
            factoryTurretAimX = target.x();
            factoryTurretAimY = target.y();
        } else if (!factoryTurretLocked) {
            factoryTurretLocked = true;
            if (target != null) {
                factoryTurretAimX = target.x() + (random.nextFloat() - 0.5f) * 0.55f;
                factoryTurretAimY = target.y() + (random.nextFloat() - 0.5f) * 0.55f;
            }
        }

        if (!factoryTurretHitApplied && gameTime >= factoryTurretFireAt) {
            factoryTurretHitApplied = true;
            fireFactoryLaserTurret();
        }
        if (gameTime >= factoryTurretFireUntil) {
            factoryTurretCharging = false;
            factoryTurretLocked = false;
            factoryTurretHitApplied = false;
            factoryTurretNextAt = gameTime + 40f + random.nextFloat() * 25f;
        }
    }

    private void fireFactoryLaserTurret() {
        playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150);
        vibrate(65);
        for (Actor enemy : enemies) {
            if (enemy != null && enemy.alive && pointNearFactoryTurretBeam(enemy.x(), enemy.y(), 0.68f)) {
                enemy.stunnedUntil = Math.max(enemy.stunnedUntil, gameTime + 2.8f);
                enemy.revealed = true;
            }
        }
        if (companion != null && companion.alive
                && pointNearFactoryTurretBeam(companion.x(), companion.y(), 0.68f)) {
            if (companionOnly) damageCompanion("被机械工厂激光炮命中");
            else companion.stunnedUntil = Math.max(companion.stunnedUntil, gameTime + 2.8f);
        }
        if (player != null && player.alive
                && pointNearFactoryTurretBeam(player.x(), player.y(), 0.68f)) {
            // 炮台命中只造成一次普通生命伤害，可被护盾、爱心和短暂无敌抵消。
            damagePlayer("被机械工厂激光炮命中");
        }
    }

    private boolean pointNearFactoryTurretBeam(float x, float y, float width) {
        if (factoryTurretX < 0) return false;
        computeFactoryTurretBeamEnd();
        float sx = factoryTurretX;
        float sy = factoryTurretY;
        float ex = factoryTurretBeamEnd[0];
        float ey = factoryTurretBeamEnd[1];
        float vx = ex - sx;
        float vy = ey - sy;
        float len2 = vx * vx + vy * vy;
        if (len2 < 0.001f) return false;
        float t = ((x - sx) * vx + (y - sy) * vy) / len2;
        if (t < 0f || t > 1f) return false;
        float px = sx + vx * t;
        float py = sy + vy * t;
        return distance(x, y, px, py) <= width;
    }

    private void computeFactoryTurretBeamEnd() {
        float sx = factoryTurretX;
        float sy = factoryTurretY;
        float dx = factoryTurretAimX - sx;
        float dy = factoryTurretAimY - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) { dx = 1f; dy = 0f; len = 1f; }
        dx /= len;
        dy /= len;
        float t = Float.MAX_VALUE;
        if (dx > 0.0001f) t = Math.min(t, (COLS - 0.5f - sx) / dx);
        else if (dx < -0.0001f) t = Math.min(t, (-0.5f - sx) / dx);
        if (dy > 0.0001f) t = Math.min(t, (mazeRows - 0.5f - sy) / dy);
        else if (dy < -0.0001f) t = Math.min(t, (-0.5f - sy) / dy);
        if (t == Float.MAX_VALUE || t < 0f) t = Math.max(COLS, mazeRows);
        factoryTurretBeamEnd[0] = sx + dx * t;
        factoryTurretBeamEnd[1] = sy + dy * t;
    }

    private boolean factoryLaserHits(float ax, float ay, int cx, int cy, float radius) {
        if (Math.abs(ay - cy) <= 0.55f) {
            int tx = clamp(Math.round(ax), 0, COLS - 1);
            int delta = tx - cx;
            if (Math.abs(delta) <= radius && clearLaserLine(cx, cy, Integer.signum(delta), 0, Math.abs(delta))) return true;
        }
        if (Math.abs(ax - cx) <= 0.55f) {
            int ty = clamp(Math.round(ay), 0, mazeRows - 1);
            int delta = ty - cy;
            if (Math.abs(delta) <= radius && clearLaserLine(cx, cy, 0, Integer.signum(delta), Math.abs(delta))) return true;
        }
        return false;
    }

    private boolean clearLaserLine(int cx, int cy, int dx, int dy, int steps) {
        if (steps == 0) return true;
        for (int i = 1; i <= steps; i++) {
            int x = cx + dx * i;
            int y = cy + dy * i;
            if (x < 0 || x >= COLS || y < 0 || y >= mazeRows || isWall(x, y)) return false;
        }
        return true;
    }

    private int laserReach(int cx, int cy, int dx, int dy, float radius) {
        int max = Math.max(1, (int) Math.floor(radius));
        int reach = 0;
        for (int i = 1; i <= max; i++) {
            int x = cx + dx * i;
            int y = cy + dy * i;
            if (x < 0 || x >= COLS || y < 0 || y >= mazeRows || isWall(x, y)) break;
            reach = i;
        }
        return reach;
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void drawFactoryLaserTurret(Canvas canvas) {
        if (factoryTurretX < 0 || factoryTurretY < 0) return;
        float sx = gx(factoryTurretX);
        float sy = gy(factoryTurretY);
        float aimDx = factoryTurretAimX - factoryTurretX;
        float aimDy = factoryTurretAimY - factoryTurretY;
        float angle = (float) Math.atan2(aimDy, aimDx);

        paint.setColor(0xEE263942);
        canvas.drawCircle(sx, sy, cell * 0.47f, paint);
        strokePaint.setColor(0xFF8DEBF0);
        strokePaint.setStrokeWidth(cell * 0.08f);
        canvas.drawCircle(sx, sy, cell * 0.47f, strokePaint);
        paint.setColor(0xFFFFC65A);
        canvas.drawCircle(sx, sy, cell * 0.17f, paint);
        float barrelX = sx + (float) Math.cos(angle) * cell * 0.48f;
        float barrelY = sy + (float) Math.sin(angle) * cell * 0.48f;
        strokePaint.setColor(0xFFD8F9FF);
        strokePaint.setStrokeWidth(cell * 0.22f);
        canvas.drawLine(sx, sy, barrelX, barrelY, strokePaint);

        if (!factoryTurretCharging) return;
        computeFactoryTurretBeamEnd();
        float ex = gxFloat(factoryTurretBeamEnd[0]);
        float ey = gyFloat(factoryTurretBeamEnd[1]);
        boolean firing = gameTime >= factoryTurretFireAt;
        float charge = clamp01((gameTime - factoryTurretChargeStart)
                / Math.max(0.1f, factoryTurretFireAt - factoryTurretChargeStart));
        float pulse = 0.65f + 0.35f * (float) Math.sin(gameTime * 18f);
        if (firing) {
            strokePaint.setColor(0x66FFF4C4);
            strokePaint.setStrokeWidth(cell * 1.08f);
            canvas.drawLine(sx, sy, ex, ey, strokePaint);
            strokePaint.setColor(0xFFFFE063);
            strokePaint.setStrokeWidth(cell * 0.68f);
            canvas.drawLine(sx, sy, ex, ey, strokePaint);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setStrokeWidth(cell * 0.22f);
            canvas.drawLine(sx, sy, ex, ey, strokePaint);
        } else {
            int alpha = (int) (55 + 145 * charge * pulse);
            strokePaint.setColor(Color.argb(alpha, 255, factoryTurretLocked ? 112 : 215, 92));
            strokePaint.setStrokeWidth(cell * (0.08f + charge * 0.18f));
            canvas.drawLine(sx, sy, ex, ey, strokePaint);
            paint.setColor(Color.argb(80 + (int) (120 * charge), 255, 214, 92));
            canvas.drawCircle(sx, sy, cell * (0.22f + 0.16f * pulse), paint);
        }
    }

    private void drawFactoryConveyors(Canvas canvas) {
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) {
                byte axis = factoryConveyors[y][x];
                if (axis == 0 || isWall(x, y)) continue;
                float cx = gx(x);
                float cy = gy(y);
                paint.setColor(0xB52A4049);
                tmpRect.set(cx - cell * 0.45f, cy - cell * 0.34f,
                        cx + cell * 0.45f, cy + cell * 0.34f);
                canvas.drawRoundRect(tmpRect, cell * 0.10f, cell * 0.10f, paint);
                strokePaint.setColor(0xAA80E8EC);
                strokePaint.setStrokeWidth(cell * 0.055f);
                canvas.drawRoundRect(tmpRect, cell * 0.10f, cell * 0.10f, strokePaint);
                float phase = (gameTime * 3.2f + x * 0.31f + y * 0.17f) % 1f;
                paint.setColor(0xCCFFC65A);
                if (axis == 1) {
                    float px = cx - cell * 0.32f + phase * cell * 0.64f;
                    drawTriangle(canvas, px, cy, cell * 0.10f, Dir.RIGHT);
                    for (int k = -1; k <= 1; k++) {
                        paint.setColor(0xFF17262D);
                        canvas.drawCircle(cx + k * cell * 0.25f, cy + cell * 0.26f, cell * 0.055f, paint);
                    }
                } else {
                    float py = cy + cell * 0.32f - phase * cell * 0.64f;
                    paint.setColor(0xCCFFC65A);
                    drawTriangle(canvas, cx, py, cell * 0.10f, Dir.UP);
                    for (int k = -1; k <= 1; k++) {
                        paint.setColor(0xFF17262D);
                        canvas.drawCircle(cx + cell * 0.26f, cy + k * cell * 0.25f, cell * 0.055f, paint);
                    }
                }
            }
        }
    }

    private void drawFactoryManufacturingPoint(Canvas canvas) {
        if (factoryMakerX < 0) return;
        float x = gx(factoryMakerX);
        float y = gy(factoryMakerY);
        float pulse = 0.92f + 0.08f * (float) Math.sin(gameTime * 5f);
        paint.setColor(companionSummoned ? 0x553DD7A5 : 0x6649D9E2);
        canvas.drawCircle(x, y, cell * 0.55f * pulse, paint);
        strokePaint.setColor(companionSummoned ? 0xFF67F0C0 : 0xFF7CF1F3);
        strokePaint.setStrokeWidth(cell * 0.09f);
        canvas.drawCircle(x, y, cell * 0.44f, strokePaint);
        drawGear(canvas, x, y, cell * 0.23f,
                companionSummoned ? 0xFF67F0C0 : 0xFFFFC65A, 0xFF29434C);
        if (!companionSummoned && factoryMakerProgress > 0f) {
            strokePaint.setColor(0xFFFFE18A);
            strokePaint.setStrokeWidth(cell * 0.12f);
            tmpRect.set(x - cell * 0.58f, y - cell * 0.58f, x + cell * 0.58f, y + cell * 0.58f);
            canvas.drawArc(tmpRect, -90f, 360f * clamp01(factoryMakerProgress / 3f), false, strokePaint);
        }
        drawCenteredText(canvas, companionSummoned ? "已制造" : "制造点",
                x, y - cell * 0.78f, cell * 0.34f,
                companionSummoned ? 0xFF86F2CE : 0xFFD8FFFF);
    }

    private void drawFactoryChargeBase(Canvas canvas) {
        if (difficulty != Difficulty.HARD) return;
        float x = gx(factoryBaseX);
        float y = gy(factoryBaseY);
        int active = factoryCharging ? 2 : factoryReturningToCharge ? 1 : 0;
        paint.setColor(active == 0 ? 0x3339D8E1 : active == 1 ? 0x667BE9ED : 0x88FFD05A);
        canvas.drawCircle(x, y, cell * (active == 2 ? 1.05f : 0.78f), paint);
        strokePaint.setStrokeWidth(cell * 0.08f);
        strokePaint.setColor(active == 2 ? 0xFFFFE58A : 0xFF78E9ED);
        canvas.drawCircle(x, y, cell * 0.62f, strokePaint);
        if (active == 2) {
            for (int i = 0; i < 4; i++) {
                float a = gameTime * 2.8f + i * (float) Math.PI / 2f;
                paint.setColor(0xFFFFD05A);
                canvas.drawCircle(x + (float) Math.cos(a) * cell * 0.48f,
                        y + (float) Math.sin(a) * cell * 0.48f, cell * 0.09f, paint);
            }
            drawCenteredText(canvas, "分批充电 " + Math.max(0, (int) Math.ceil(factoryChargeUntil - gameTime)) + "秒",
                    x, y - cell * 0.88f, cell * 0.25f, 0xFFFFF1B2);
        } else if (active == 1) {
            drawCenteredText(canvas, "返厂基地", x, y - cell * 0.82f, cell * 0.24f, 0xFFD9FFFF);
        }
    }

    private void drawFactoryArmAttack(Canvas canvas) {
        if (!factoryArmPullActive || factoryArmEnemyKind < 0 || factoryArmEnemyKind >= enemies.length) return;
        Actor enemy = enemies[factoryArmEnemyKind];
        if (enemy == null || player == null) return;
        float sx = gxFloat(enemy.x());
        float sy = gyFloat(enemy.y());
        float tx = gxFloat(player.x());
        float ty = gyFloat(player.y());
        strokePaint.setStrokeWidth(cell * 0.18f);
        strokePaint.setColor(0xFFB8D6D8);
        canvas.drawLine(sx, sy, tx, ty, strokePaint);
        strokePaint.setStrokeWidth(cell * 0.065f);
        strokePaint.setColor(0xFFFFC65A);
        canvas.drawLine(sx, sy, tx, ty, strokePaint);
        for (int i = 1; i <= 3; i++) {
            float p = i / 4f;
            paint.setColor(0xFF3A535B);
            canvas.drawCircle(lerp(sx, tx, p), lerp(sy, ty, p), cell * 0.16f, paint);
        }
        paint.setColor(0xFFFF795E);
        canvas.drawCircle(tx, ty, cell * 0.22f, paint);
    }

    private void drawRobotCompanion(Canvas canvas, float x, float y, float r, Dir dir) {
        paint.setColor(0x4467F0C0);
        canvas.drawCircle(x, y, r * 1.35f, paint);
        paint.setColor(0xFF2A5960);
        tmpRect.set(x - r * 0.78f, y - r * 0.63f, x + r * 0.78f, y + r * 0.63f);
        canvas.drawRoundRect(tmpRect, r * 0.24f, r * 0.24f, paint);
        strokePaint.setColor(0xFF8AFFF0);
        strokePaint.setStrokeWidth(r * 0.11f);
        canvas.drawRoundRect(tmpRect, r * 0.24f, r * 0.24f, strokePaint);
        float dx = dir.dx * r * 0.09f;
        float dy = dir.dy * r * 0.07f;
        paint.setColor(0xFF10272B);
        tmpRect.set(x - r * 0.52f + dx, y - r * 0.34f + dy,
                x + r * 0.52f + dx, y + r * 0.12f + dy);
        canvas.drawRoundRect(tmpRect, r * 0.12f, r * 0.12f, paint);
        paint.setColor(0xFF67F0C0);
        canvas.drawCircle(x - r * 0.24f + dx, y - r * 0.10f + dy, r * 0.10f, paint);
        canvas.drawCircle(x + r * 0.24f + dx, y - r * 0.10f + dy, r * 0.10f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x + r * 0.07f, y + r * 0.38f, r * 0.08f, paint);
        strokePaint.setColor(0xFFBFFFF2);
        strokePaint.setStrokeWidth(r * 0.10f);
        canvas.drawLine(x, y - r * 0.63f, x, y - r * 0.94f, strokePaint);
        paint.setColor(0xFFFFD05A);
        canvas.drawCircle(x, y - r * 0.98f, r * 0.11f, paint);
    }

    private void drawFactoryIcon(Canvas canvas, float x, float y, float size) {
        paint.setColor(0x553FE6EC);
        canvas.drawCircle(x, y, size * 1.05f, paint);
        paint.setColor(0xFF314C57);
        tmpRect.set(x - size * 0.76f, y - size * 0.55f,
                x + size * 0.76f, y + size * 0.48f);
        canvas.drawRoundRect(tmpRect, size * 0.22f, size * 0.22f, paint);
        strokePaint.setStrokeWidth(size * 0.11f);
        strokePaint.setColor(0xFF8DF2F4);
        canvas.drawRoundRect(tmpRect, size * 0.22f, size * 0.22f, strokePaint);
        paint.setColor(0xFF17272E);
        tmpRect.set(x - size * 0.52f, y - size * 0.31f,
                x + size * 0.52f, y + size * 0.10f);
        canvas.drawRoundRect(tmpRect, size * 0.13f, size * 0.13f, paint);
        paint.setColor(0xFFFFD05A);
        canvas.drawCircle(x - size * 0.24f, y - size * 0.10f, size * 0.10f, paint);
        canvas.drawCircle(x + size * 0.24f, y - size * 0.10f, size * 0.10f, paint);
        strokePaint.setColor(0xFFB8EEF0);
        strokePaint.setStrokeWidth(size * 0.09f);
        canvas.drawLine(x, y - size * 0.55f, x, y - size * 0.86f, strokePaint);
        paint.setColor(0xFFFF795E);
        canvas.drawCircle(x, y - size * 0.90f, size * 0.11f, paint);
        paint.setColor(0xFFFFB74D);
        canvas.drawRect(x - size * 0.42f, y + size * 0.24f,
                x + size * 0.42f, y + size * 0.38f, paint);
    }

    private void drawGearPellet(Canvas canvas, float x, float y, float size, boolean power) {
        int outer = power ? 0xFFFFC65A : 0xFFB9E6E8;
        int inner = power ? 0xFF6A4A1D : 0xFF31525B;
        drawGear(canvas, x, y, size, outer, inner);
        if (power) {
            paint.setColor(0x55FFD36A);
            canvas.drawCircle(x, y, size * 1.55f, paint);
        }
    }

    private void drawGear(Canvas canvas, float x, float y, float r, int outerColor, int innerColor) {
        int save = canvas.save();
        canvas.rotate(gameTime * 38f, x, y);
        paint.setColor(outerColor);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            float cx = x + (float) Math.cos(a) * r * 0.82f;
            float cy = y + (float) Math.sin(a) * r * 0.82f;
            canvas.save();
            canvas.rotate(i * 45f, cx, cy);
            canvas.drawRect(cx - r * 0.19f, cy - r * 0.31f,
                    cx + r * 0.19f, cy + r * 0.31f, paint);
            canvas.restore();
        }
        canvas.drawCircle(x, y, r * 0.78f, paint);
        paint.setColor(innerColor);
        canvas.drawCircle(x, y, r * 0.34f, paint);
        canvas.restoreToCount(save);
    }

    private void drawFactoryCentralDoor(Canvas canvas) {
        float x = gx(FACTORY_DOOR_X);
        float y = gy(FACTORY_DOOR_Y);
        boolean open = escapeActive && escapeUnlocked;
        float w = cell * 3.15f;
        float h = cell * 2.30f;
        float pulse = 0.85f + 0.15f * (float) Math.sin(gameTime * 6f);

        // 门框始终存在；开门后中间呈现明亮的逃生通道。
        paint.setColor(0xCC142A33);
        tmpRect.set(x - w * 0.58f, y - h * 0.62f, x + w * 0.58f, y + h * 0.62f);
        canvas.drawRoundRect(tmpRect, cell * 0.24f, cell * 0.24f, paint);
        strokePaint.setStrokeWidth(cell * 0.13f);
        strokePaint.setColor(open ? 0xFFFFC65A : 0xFF6DDCE5);
        canvas.drawRoundRect(tmpRect, cell * 0.24f, cell * 0.24f, strokePaint);

        if (open) {
            paint.setShader(new RadialGradient(x, y, cell * 2.0f,
                    0xDDFFF0A6, 0x334BDDE8, Shader.TileMode.CLAMP));
            tmpRect.inset(cell * 0.38f, cell * 0.36f);
            canvas.drawRoundRect(tmpRect, cell * 0.18f, cell * 0.18f, paint);
            paint.setShader(null);
            paint.setColor(0x88FFF2A0);
            canvas.drawCircle(x, y, cell * 0.72f * pulse, paint);
            drawCenteredText(canvas, "中央出口", x, y - h * 0.77f,
                    cell * 0.30f, 0xFFFFF4C2);
            drawCenteredText(canvas, "OPEN", x, y + cell * 0.18f,
                    cell * 0.42f, Color.WHITE);
        } else {
            paint.setColor(0xFF365763);
            tmpRect.set(x - w * 0.47f, y - h * 0.47f, x + w * 0.47f, y + h * 0.47f);
            canvas.drawRoundRect(tmpRect, cell * 0.16f, cell * 0.16f, paint);
            paint.setColor(0xFF223A44);
            canvas.drawRect(x - cell * 0.07f, y - h * 0.44f,
                    x + cell * 0.07f, y + h * 0.44f, paint);
            for (int side = -1; side <= 1; side += 2) {
                paint.setColor(0xFFFFB74D);
                canvas.drawRect(x + side * w * 0.31f - cell * 0.17f, y - h * 0.34f,
                        x + side * w * 0.31f + cell * 0.17f, y + h * 0.34f, paint);
                paint.setColor(0xFF2B414A);
                for (int k = -2; k <= 2; k++) {
                    float sy = y + k * cell * 0.30f;
                    canvas.drawRect(x + side * w * 0.31f - cell * 0.24f, sy - cell * 0.06f,
                            x + side * w * 0.31f + cell * 0.24f, sy + cell * 0.06f, paint);
                }
            }
            paint.setColor(((int) (gameTime * 2.5f) & 1) == 0 ? 0xFFFF6B5E : 0xFF783B39);
            canvas.drawCircle(x, y - h * 0.27f, cell * 0.16f, paint);
            drawCenteredText(canvas, "LOCK", x, y + h * 0.23f,
                    cell * 0.34f, 0xFFC8D8DB);
            drawCenteredText(canvas, "收集全部齿轮豆后开启", x, y - h * 0.78f,
                    cell * 0.26f, 0xFFD7F1F2);
        }
    }

    private void drawRobotEnemy(Canvas canvas, float x, float y, float r, int color,
                                boolean frightened, int kind, Dir dir) {
        int body = frightened ? 0xFF345BE8 : color;
        float bob = (float) Math.sin(gameTime * 8f + kind) * r * 0.055f;
        y += bob;
        // 方向通过面板位置和履带/推进器偏移表现，不再沿用幽灵轮廓。
        float dx = dir == null ? 0f : dir.dx * r * 0.10f;
        float dy = dir == null ? 0f : dir.dy * r * 0.08f;
        paint.setColor(0xFF26333A);
        tmpRect.set(x - r * 0.92f, y + r * 0.58f, x + r * 0.92f, y + r * 0.92f);
        canvas.drawRoundRect(tmpRect, r * 0.18f, r * 0.18f, paint);
        paint.setColor(body);
        tmpRect.set(x - r * 0.78f, y - r * 0.62f, x + r * 0.78f, y + r * 0.67f);
        canvas.drawRoundRect(tmpRect, r * 0.24f, r * 0.24f, paint);
        strokePaint.setStrokeWidth(r * 0.11f);
        strokePaint.setColor(0xFFB9EEF0);
        canvas.drawRoundRect(tmpRect, r * 0.24f, r * 0.24f, strokePaint);
        paint.setColor(0xFF15242B);
        tmpRect.set(x - r * 0.56f + dx, y - r * 0.38f + dy,
                x + r * 0.56f + dx, y + r * 0.14f + dy);
        canvas.drawRoundRect(tmpRect, r * 0.16f, r * 0.16f, paint);
        int eye = frightened ? Color.WHITE : 0xFFFFE16A;
        paint.setColor(eye);
        canvas.drawCircle(x - r * 0.25f + dx, y - r * 0.12f + dy, r * 0.11f, paint);
        canvas.drawCircle(x + r * 0.25f + dx, y - r * 0.12f + dy, r * 0.11f, paint);
        paint.setColor(0xFF0B1419);
        canvas.drawCircle(x - r * 0.25f + dx, y - r * 0.12f + dy, r * 0.045f, paint);
        canvas.drawCircle(x + r * 0.25f + dx, y - r * 0.12f + dy, r * 0.045f, paint);
        strokePaint.setStrokeWidth(r * 0.10f);
        strokePaint.setColor(0xFFB9EEF0);
        canvas.drawLine(x, y - r * 0.62f, x, y - r * 0.91f, strokePaint);
        paint.setColor(kind == 4 ? 0xFF7EE06B : 0xFFFF6B5E);
        canvas.drawCircle(x, y - r * 0.96f, r * 0.10f, paint);
        if (frightened) {
            drawCenteredText(canvas, "!", x, y + r * 0.47f, r * 0.42f, Color.WHITE);
        } else {
            paint.setColor(0xFF89E7EB);
            canvas.drawCircle(x, y + r * 0.40f, r * 0.09f, paint);
        }
    }

    private void drawGuide(Canvas canvas) {
        drawMenuBackground(canvas);
        drawBackButton(canvas, "返回");
        drawCenteredText(canvas, "玩法、地图机制与 AI", viewW * 0.5f, viewH * 0.078f,
                viewW * 0.047f, Color.WHITE);

        float left = viewW * 0.045f;
        float right = viewW * 0.955f;
        float top = viewH * 0.115f;
        float gap = viewW * 0.018f;
        float cardW = (right - left - gap) / 2f;
        float cardH = viewH * 0.175f;

        tmpRect.set(left, top, right, top + viewH * 0.087f);
        paint.setColor(0xB31A173B);
        canvas.drawRoundRect(tmpRect, viewW * 0.024f, viewW * 0.024f, paint);
        strokePaint.setColor(0x667D8CFF);
        strokePaint.setStrokeWidth(viewW * 0.002f);
        canvas.drawRoundRect(tmpRect, viewW * 0.024f, viewW * 0.024f, strokePaint);
        drawLeftText(canvas, "三档难度", left + viewW * 0.023f, top + viewH * 0.027f,
                viewW * 0.022f, 0xFFFFD05A);
        drawLeftText(canvas, "简单随机少一名敌人；中等保留四名策略敌人；困难再加一名随机游走敌人。",
                left + viewW * 0.023f, top + viewH * 0.052f, viewW * 0.0148f, Color.WHITE);
        drawLeftText(canvas, "经典困难会低概率重组地形；工厂困难另有返厂充电和直线机械臂；主题页可切换经典吃豆人与黄色小鸟两种玩家形象。",
                left + viewW * 0.023f, top + viewH * 0.074f, viewW * 0.0148f, 0xFFD7DCF4);

        float row1 = top + viewH * 0.105f;
        float row2 = row1 + cardH + viewH * 0.014f;
        float row3 = row2 + cardH + viewH * 0.014f;
        drawGuideMapCard(canvas, left, row1, cardW, cardH, "小丑乐园", 0xFFFFC857,
                new String[]{"五颗彩球各提供7秒反击，第五颗召唤小跟班。",
                        "礼盒可能给予护盾、加速、全体眩晕或伙伴；巨型小丑会挥臂攻击。",
                        "敌人会阶段性加速并隐形；清空豆子后进入彩幕门。"});
        drawGuideMapCard(canvas, left + cardW + gap, row1, cardW, cardH, "经典迷宫", 0xFF65A3FF,
                new String[]{"侧边隧道可从另一侧出来，爱心可提供一次延迟复活。",
                        "困难模式长间隔低概率换一张新地形，但剩余豆子总量不变。",
                        "清空豆子后，按指示从开放的侧边通道离场。"});
        drawGuideMapCard(canvas, left, row2, cardW, cardH, "海洋世界", 0xFF55E4EF,
                new String[]{"潮位通常逐次上涨，偶尔会短暂退潮；极低概率完全退尽，但累计涨潮阶段不会倒退。",
                        "水下敌人加速10%，玩家需管理氧气；原有反击贝壳仍提供7秒反击。",
                        "每局有40%概率让其中一枚反击贝壳替换为发光珍珠：10秒水下呼吸并永久加速10%；清空后进入顶部救生舱。"});
        drawGuideMapCard(canvas, left + cardW + gap, row2, cardW, cardH, "新春盛景", 0xFFFFD05A,
                new String[]{"普通防身武器显示为鞭炮；大型爆竹可炸开墙壁，8秒后恢复。",
                        "场上会周期性出现大爆竹，拾取后四秒爆炸；豆子清空后还要找到开门爆竹，炸开中央年门。",
                        "开门爆竹引爆时会短暂眩晕敌人，抓住这个空档进入年门逃生。"});
        drawGuideMapCard(canvas, left, row3, cardW, cardH, "银河系", 0xFFB79BFF,
                new String[]{"陨石落下前会显示落点；黑洞随机拉走玩家或聚拢敌人。",
                        "救命虫洞可能把玩家送到远处，黑洞结束后也会提供短暂安全。",
                        "清空星球后，脚下直接生成逃生虫洞。"});
        drawGuideMapCard(canvas, left + cardW + gap, row3, cardW, cardH, "机械工厂", 0xFFFFBC59,
                new String[]{"每局随机横/纵循环通道；短传送带让通过者达到2.5倍速度。",
                        "制造点每局随机一个位置并保持固定，连续站3秒可制造机器人伙伴；炮台短暂蓄力后发射粗激光。",
                        "吃掉一半后限时120/100/80秒；清空齿轮豆后中央门开启。",
                        "困难：机器人运行40秒后返厂，各自抵达后立即充电3秒，无需等待全员；还可能用无障碍直线机械臂拉近2格。"});

        float aiTop = row3 + cardH + viewH * 0.016f;
        tmpRect.set(left, aiTop, right, viewH * 0.948f);
        paint.setColor(0xB31A173B);
        canvas.drawRoundRect(tmpRect, viewW * 0.024f, viewW * 0.024f, paint);
        drawLeftText(canvas, "敌人与小跟班", left + viewW * 0.023f, aiTop + viewH * 0.027f,
                viewW * 0.021f, 0xFF67F0C0);
        drawLeftText(canvas, "红色直追、粉色预判前方、青色配合夹击、橙色远追近退；绿色敌人随机游走。",
                left + viewW * 0.023f, aiTop + viewH * 0.052f, viewW * 0.0145f, Color.WHITE);
        drawLeftText(canvas, "小跟班会规划收集路线、预测敌人、及时掉头避险，并在玩家阵亡后接管残局。",
                left + viewW * 0.023f, aiTop + viewH * 0.074f, viewW * 0.0145f, Color.WHITE);
        drawLeftText(canvas, "伙伴拥有一次炸弹/鞭炮/十字激光；敌人可反击时不会浪费。玩家摇杆可切换自动或手动。",
                left + viewW * 0.023f, aiTop + viewH * 0.096f, viewW * 0.0145f, 0xFFD7DCF4);
    }

    private void drawGuideMapCard(Canvas canvas, float left, float top, float width, float height,
                                  String title, int accent, String[] lines) {
        tmpRect.set(left, top, left + width, top + height);
        paint.setColor(0xA9232048);
        canvas.drawRoundRect(tmpRect, viewW * 0.021f, viewW * 0.021f, paint);
        strokePaint.setColor(Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)));
        strokePaint.setStrokeWidth(viewW * 0.0018f);
        canvas.drawRoundRect(tmpRect, viewW * 0.021f, viewW * 0.021f, strokePaint);
        paint.setColor(accent);
        tmpRect.set(left, top + height * 0.15f, left + viewW * 0.006f,
                top + height * 0.85f);
        canvas.drawRoundRect(tmpRect, viewW * 0.004f, viewW * 0.004f, paint);
        float x = left + viewW * 0.020f;
        float y = top + viewH * 0.027f;
        drawLeftText(canvas, title, x, y, viewW * 0.0195f, accent);
        float lineSize = lines.length >= 4 ? viewW * 0.0128f : viewW * 0.0135f;
        float lineGap = lines.length >= 4 ? viewH * 0.027f : viewH * 0.034f;
        for (String line : lines) {
            y += lineGap;
            drawLeftFittedText(canvas, "· " + line, x, y, lineSize, Color.WHITE,
                    width - viewW * 0.038f);
        }
    }

    private void drawGame(Canvas canvas) {
        drawThemeBackground(canvas);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setColor(Color.WHITE);
        ensureWallCache();
        if (wallCache != null) {
            tmpRect.set(0, 0, wallCache.getWidth() / wallCacheScale, wallCache.getHeight() / wallCacheScale);
            canvas.drawBitmap(wallCache, null, tmpRect, cachePaint);
        }
        if (theme == Theme.FACTORY) {
            drawFactoryConveyors(canvas);
            drawFactoryManufacturingPoint(canvas);
            drawFactoryChargeBase(canvas);
        }
        drawPellets(canvas);
        if (theme == Theme.FACTORY) {
            drawFactoryCentralDoor(canvas);
            drawFactoryArmAttack(canvas);
        }
        drawRestoringWalls(canvas);
        drawItems(canvas);
        drawBombs(canvas);
        drawEscapeRoute(canvas);
        if (theme == Theme.GALAXY) {
            drawMeteor(canvas);
            drawBlackHole(canvas);
        }
        if (theme == Theme.CLOWN) drawGiantClown(canvas);
        drawActors(canvas);
        if (theme == Theme.FACTORY) drawFactoryLaserTurret(canvas);
        if (theme == Theme.OCEAN) drawTide(canvas);
        drawHud(canvas);
        drawControls(canvas);
        drawBanner(canvas);
        if (reviveAt > gameTime || companionReviveAt > gameTime) drawReviveCountdown(canvas);
        if (screen == Screen.PAUSED || screen == Screen.GAME_OVER || screen == Screen.WIN) drawGameOverlay(canvas);
    }

    private void drawThemeBackground(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(backgroundShader);
        canvas.drawRect(0, 0, viewW, viewH, paint);
        paint.setShader(null);
        if (theme == Theme.CLOWN) {
            // 从迷宫中心向四周辐射的马戏团彩条，并完整覆盖上下半区。
            float rayCx = originX + COLS * cell * 0.5f;
            float rayCy = originY + mazeRows * cell * 0.5f;
            float rayRadius = (float) Math.hypot(COLS * cell, mazeRows * cell) * 0.62f;
            int[] colors = {0x1D2F8CFF, 0x1DFFD85A, 0x1DFF5A91, 0x1D4DE5EF};
            int rayCount = 20;
            for (int i = 0; i < rayCount; i++) {
                float a0 = (float) (-Math.PI / 2 + i * Math.PI * 2 / rayCount);
                float a1 = (float) (-Math.PI / 2 + (i + 1) * Math.PI * 2 / rayCount);
                path.reset();
                path.moveTo(rayCx, rayCy);
                path.lineTo(rayCx + (float) Math.cos(a0) * rayRadius,
                        rayCy + (float) Math.sin(a0) * rayRadius);
                path.lineTo(rayCx + (float) Math.cos(a1) * rayRadius,
                        rayCy + (float) Math.sin(a1) * rayRadius);
                path.close();
                paint.setColor(colors[i % colors.length]);
                canvas.drawPath(path, paint);
            }
            strokePaint.setColor(0x22FFE18A);
            strokePaint.setStrokeWidth(Math.max(1f, cell * 0.08f));
            canvas.drawCircle(rayCx, rayCy, cell * 4.2f, strokePaint);
            canvas.drawCircle(rayCx, rayCy, cell * 8.4f, strokePaint);
            drawBalloon(canvas, originX + cell * 2f, originY + cell * 4f, cell * 0.55f, 0xFFFF5A8D);
            drawBalloon(canvas, originX + cell * 22f, originY + cell * 8f, cell * 0.55f, 0xFF65E6FF);
            drawBalloon(canvas, originX + cell * 4f, originY + cell * 23f, cell * 0.50f, 0xFFFFD45B);
            drawBalloon(canvas, originX + cell * 20f, originY + cell * 25f, cell * 0.50f, 0xFF72E7A6);
        } else if (theme == Theme.OCEAN) {
            paint.setColor(0x2234C9E8);
            strokePaint.setStrokeWidth(cell * 0.08f);
            strokePaint.setColor(0x443DE6FF);
            for (int i = 0; i < 5; i++) {
                float y = originY + cell * (3 + i * 6);
                path.reset();
                path.moveTo(originX, y);
                for (int x = 0; x <= 20; x++) {
                    float px = originX + x * cell * 1.25f;
                    float py = y + (float) Math.sin(x * 0.9 + gameTime) * cell * 0.22f;
                    path.lineTo(px, py);
                }
                canvas.drawPath(path, strokePaint);
            }
        } else if (theme == Theme.SPRING) {
            drawFireworks(canvas);
            paint.setColor(0x22FFD060);
            for (int i = 0; i < 8; i++) {
                float y = originY + (i * 3.6f + 1f) * cell;
                canvas.drawCircle(originX + cell * 0.45f, y, cell * 0.12f, paint);
                canvas.drawCircle(originX + cell * 24.55f, y, cell * 0.12f, paint);
            }
        } else if (theme == Theme.FACTORY) {
            // 明亮的钢板底色、传送带线与警示灯，和其他主题形成明显区分。
            paint.setColor(0x224DD0E1);
            for (int y = 2; y < ROWS; y += 4) {
                float py = originY + y * cell;
                canvas.drawRect(originX, py - cell * 0.10f, originX + COLS * cell, py + cell * 0.10f, paint);
            }
            strokePaint.setStrokeWidth(Math.max(1f, cell * 0.055f));
            strokePaint.setColor(0x4478E7F0);
            for (int x = 2; x < COLS; x += 5) {
                float px = originX + x * cell;
                canvas.drawLine(px, mazeTop, px, mazeBottom, strokePaint);
            }
            for (int i = 0; i < 7; i++) {
                float px = originX + cell * (2.3f + i * 3.45f);
                float py = originY + cell * (1.0f + (i & 1) * 26f);
                paint.setColor(((int) (gameTime * 3f) + i) % 2 == 0 ? 0xAAFFB74D : 0x556AE4EE);
                canvas.drawCircle(px, py, cell * 0.16f, paint);
            }
        } else if (theme == Theme.GALAXY) {
            float t = gameTime;
            for (int i = 0; i < starX.length; i++) {
                int alpha = 90 + (int) (100 * (0.5 + 0.5 * Math.sin(t * 1.6 + i)));
                paint.setColor(Color.argb(alpha, 210, 225, 255));
                canvas.drawCircle(starX[i] * viewW, starY[i] * controlTop,
                        starSize[i] * cell * 0.06f, paint);
            }
            paint.setShader(galaxyShader);
            canvas.save();
            canvas.rotate(-18f, viewW / 2f, (mazeTop + mazeBottom) / 2f);
            canvas.drawOval(viewW * 0.08f, (mazeTop + mazeBottom) / 2f - cell * 2.3f,
                    viewW * 0.92f, (mazeTop + mazeBottom) / 2f + cell * 2.3f, paint);
            canvas.restore();
            paint.setShader(null);
        }
    }

    private void ensureWallCache() {
        if ((!wallCacheDirty && wallCache != null) || viewW <= 0 || controlTop <= 0) return;
        wallCacheScale = Math.min(1f, 1080f / viewW);
        int width = Math.max(1, Math.round(viewW * wallCacheScale));
        int height = Math.max(1, (int) Math.ceil(controlTop * wallCacheScale));
        if (wallCache == null || wallCache.getWidth() != width || wallCache.getHeight() != height) {
            wallCache = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            wallCacheCanvas = new Canvas(wallCache);
            wallCacheCanvas.scale(wallCacheScale, wallCacheScale);
        } else wallCache.eraseColor(Color.TRANSPARENT);
        for (int y = 0; y < mazeRows; y++) for (int x = 0; x < COLS; x++) {
            if (isWall(x, y)) drawWallCell(wallCacheCanvas, x, y);
            else if (pellets[y][x]) drawStaticPellet(wallCacheCanvas, x, y);
        }
        wallCacheDirty = false;
        resetDrawingState();
    }

    private void drawStaticPellet(Canvas canvas, int x, int y) {
        if (theme == Theme.CLOWN) drawCarnivalDot(canvas, gx(x), gy(y), cell * 0.105f, DOT_COLORS[(x + y) & 3]);
        else drawThemePellet(canvas, gx(x), gy(y), cell * 0.115f, false);
    }

    private void eraseCachedPellet(int x, int y) {
        if (wallCacheCanvas == null || wallCacheDirty) return;
        // Clear only the pellet's cell interior, preserving adjacent wall anti-aliasing.
        float pad = cell * 0.32f;
        wallCacheCanvas.drawRect(gx(x) - pad, gy(y) - pad, gx(x) + pad, gy(y) + pad, clearPaint);
    }

    private void drawWallCell(Canvas canvas, int x, int y) {
        float l = originX + x * cell;
        float t = originY + y * cell;
        float inset = cell * 0.045f;
        tmpRect.set(l + inset, t + inset, l + cell - inset, t + cell - inset);
        int fill;
        int line;
        switch (theme) {
            case CLOWN: fill = ((x + y) & 1) == 0 ? 0xFF61217B : 0xFF442069; line = 0xFFFFC857; break;
            case CLASSIC: fill = 0xFF08255E; line = 0xFF257CFF; break;
            case OCEAN: fill = ((x * 3 + y) % 5 == 0) ? 0xFF0C6A79 : 0xFF07536E; line = 0xFF37D3D7; break;
            case SPRING: fill = 0xFF8D1420; line = 0xFFFFC94F; break;
            case FACTORY: fill = ((x + y) & 1) == 0 ? 0xFF254B58 : 0xFF1D3D49; line = 0xFF73DDE7; break;
            default: fill = 0xFF241258; line = 0xFF8E76FF; break;
        }
        paint.setColor(fill);
        canvas.drawRoundRect(tmpRect, cell * 0.19f, cell * 0.19f, paint);
        strokePaint.setStrokeWidth(Math.max(1f, cell * 0.075f));
        strokePaint.setColor(line);
        canvas.drawRoundRect(tmpRect, cell * 0.19f, cell * 0.19f, strokePaint);
        strokePaint.setColor(0x35FFFFFF);
        strokePaint.setStrokeWidth(Math.max(1f, cell * 0.035f));
        canvas.drawLine(l + cell * 0.23f, t + cell * 0.18f, l + cell * 0.77f, t + cell * 0.18f, strokePaint);
        if (theme == Theme.SPRING && (x * 13 + y * 7) % 43 == 0 && cell > 15f) {
            drawCenteredText(canvas, (x + y) % 2 == 0 ? "春" : "福",
                    l + cell / 2f, t + cell * 0.73f, cell * 0.48f, 0xFFFFD96A);
        }
        if (theme == Theme.OCEAN && (x * 11 + y * 5) % 37 == 0) {
            paint.setColor(0xFF76E6D1);
            canvas.drawCircle(l + cell * 0.5f, t + cell * 0.5f, cell * 0.12f, paint);
        }
        if (theme == Theme.FACTORY) {
            paint.setColor(0xFF9CD8D9);
            canvas.drawCircle(l + cell * 0.22f, t + cell * 0.22f, cell * 0.055f, paint);
            canvas.drawCircle(l + cell * 0.78f, t + cell * 0.78f, cell * 0.055f, paint);
            if ((x * 7 + y * 11) % 31 == 0) {
                paint.setColor(0xFFFFB74D);
                canvas.drawRect(l + cell * 0.18f, t + cell * 0.43f,
                        l + cell * 0.82f, t + cell * 0.57f, paint);
            }
        }
    }

    private void drawPellets(Canvas canvas) {
        float pulse = 0.82f + 0.18f * (float) Math.sin(gameTime * 5f);
        for (int y = 0; y < mazeRows; y++) {
            for (int x = 0; x < COLS; x++) {
                float px = gx(x);
                float py = gy(y);
                if (powerPellets[y][x]) {
                    paint.setColor(Color.argb(60, 255, 255, 255));
                    canvas.drawCircle(px, py, cell * 0.34f * pulse, paint);
                    if (theme == Theme.CLOWN) {
                        int color = clownBallColors[y][x] != 0 ? clownBallColors[y][x] : 0xFFFFD25B;
                        drawClownBall(canvas, px, py, cell * 0.24f, color, true);
                    } else if (theme == Theme.OCEAN && x == oceanPearlX && y == oceanPearlY) {
                        drawPearl(canvas, px, py, cell * 0.22f * 0.95f);
                    } else {
                        drawThemePellet(canvas, px, py, cell * 0.22f, true);
                    }
                }
            }
        }
    }

    private void drawThemePellet(Canvas canvas, float x, float y, float size, boolean power) {
        switch (theme) {
            case CLOWN:
                if (power) drawClownBall(canvas, x, y, size, Color.WHITE, true);
                else drawCarnivalDot(canvas, x, y, size, 0xFFFFD25B);
                break;
            case CLASSIC:
                paint.setColor(power ? 0xFFFFF5D0 : 0xFFFFDFA8);
                canvas.drawCircle(x, y, size * (power ? 0.9f : 0.55f), paint);
                break;
            case OCEAN:
                if (power) drawShell(canvas, x, y, size, true);
                else drawShell(canvas, x, y, size, false);
                break;
            case SPRING: drawFirecracker(canvas, x, y, size, power); break;
            case GALAXY: drawPlanet(canvas, x, y, size * (power ? 1.05f : 0.75f),
                    power ? 0xFFFFD36A : 0xFF6BC8FF); break;
            case FACTORY: drawGearPellet(canvas, x, y, size * (power ? 1.15f : 0.92f), power); break;
        }
    }

    private void drawRestoringWalls(Canvas canvas) {
        if (brokenWallCount == 0) return;
        for (int y = 1; y < mazeRows - 1; y++) {
            for (int x = 1; x < COLS - 1; x++) {
                if (brokenUntil[y][x] <= gameTime) continue;
                float remain = brokenUntil[y][x] - gameTime;
                float px = gx(x);
                float py = gy(y);
                paint.setColor(remain < 2f && ((int) (gameTime * 7) & 1) == 0 ? 0xAAFFD45B : 0x665E3029);
                canvas.drawCircle(px - cell * 0.18f, py + cell * 0.23f, cell * 0.13f, paint);
                canvas.drawCircle(px + cell * 0.17f, py + cell * 0.27f, cell * 0.11f, paint);
            }
        }
    }

    private void drawItems(Canvas canvas) {
        for (Item item : items) {
            float x = gx(item.x);
            float y = gy(item.y);
            float pulse = 1f + 0.08f * (float) Math.sin(gameTime * 6f + item.x);
            paint.setColor(0x44FFFFFF);
            canvas.drawCircle(x, y, cell * 0.46f * pulse, paint);
            switch (item.type) {
                case GIFT: drawGift(canvas, x, y, cell * 0.30f); break;
                case HEART: drawHeart(canvas, x, y, cell * 0.30f, 0xFFFF4D7E); break;
                case BIG_CRACKER: drawFirecracker(canvas, x, y, cell * 0.35f, true); break;
                case WORMHOLE: drawWormhole(canvas, x, y, cell * 0.34f); break;
                case GATE_CRACKER:
                    paint.setColor(0x44FFD45B);
                    canvas.drawCircle(x, y, cell * 0.52f * pulse, paint);
                    drawFirecracker(canvas, x, y, cell * 0.39f, true);
                    break;
                case FAIRY_WAND:
                    drawFairyWand(canvas, x, y, cell * 0.36f * pulse);
                    break;
            }
        }
    }

    private void drawBombs(Canvas canvas) {
        for (Bomb bomb : activeBombs) {
            float x = gx(bomb.x);
            float y = gy(bomb.y);
            boolean factoryLaser = theme == Theme.FACTORY && !bomb.big;
            if (!bomb.exploded) {
                float left = Math.max(0f, bomb.explodeAt - gameTime);
                float pulse = 1f + 0.09f * (float) Math.sin(gameTime * 13f);
                if (bomb.big) {
                    drawFirecracker(canvas, x, y, cell * 0.39f * pulse, true);
                    strokePaint.setColor(0x88FFD458);
                    strokePaint.setStrokeWidth(cell * 0.07f);
                    canvas.drawCircle(x, y, bomb.radius * cell, strokePaint);
                    drawCenteredText(canvas, String.valueOf((int) Math.ceil(left)), x,
                            y - cell * 0.55f, cell * 0.42f, Color.WHITE);
                } else if (factoryLaser) {
                    paint.setColor(0xFF263B44);
                    tmpRect.set(x - cell * 0.32f, y - cell * 0.25f,
                            x + cell * 0.32f, y + cell * 0.25f);
                    canvas.drawRoundRect(tmpRect, cell * 0.09f, cell * 0.09f, paint);
                    strokePaint.setColor(0x996CEEF2);
                    strokePaint.setStrokeWidth(cell * 0.055f);
                    int l = laserReach(bomb.x, bomb.y, -1, 0, bomb.radius);
                    int r = laserReach(bomb.x, bomb.y, 1, 0, bomb.radius);
                    int u = laserReach(bomb.x, bomb.y, 0, -1, bomb.radius);
                    int d = laserReach(bomb.x, bomb.y, 0, 1, bomb.radius);
                    canvas.drawLine(x - l * cell, y, x + r * cell, y, strokePaint);
                    canvas.drawLine(x, y - u * cell, x, y + d * cell, strokePaint);
                } else if (theme == Theme.SPRING) {
                    drawFirecracker(canvas, x, y, cell * 0.31f * pulse, false);
                } else {
                    paint.setColor(0xFF24242E);
                    canvas.drawCircle(x, y, cell * 0.30f * pulse, paint);
                    paint.setColor(0xFFFFD65C);
                    canvas.drawCircle(x + cell * 0.19f, y - cell * 0.26f, cell * 0.075f, paint);
                }
            } else if (factoryLaser) {
                float p = clamp01((bomb.vanishAt - gameTime) / 0.58f);
                int l = laserReach(bomb.x, bomb.y, -1, 0, bomb.radius);
                int r = laserReach(bomb.x, bomb.y, 1, 0, bomb.radius);
                int u = laserReach(bomb.x, bomb.y, 0, -1, bomb.radius);
                int d = laserReach(bomb.x, bomb.y, 0, 1, bomb.radius);
                strokePaint.setStrokeWidth(cell * (0.18f + 0.12f * p));
                strokePaint.setColor(Color.argb((int) (230 * p), 104, 247, 255));
                canvas.drawLine(x - l * cell, y, x + r * cell, y, strokePaint);
                canvas.drawLine(x, y - u * cell, x, y + d * cell, strokePaint);
                paint.setColor(Color.argb((int) (180 * p), 255, 230, 116));
                canvas.drawCircle(x, y, cell * (0.38f + 0.28f * p), paint);
            } else {
                float p = clamp01((bomb.vanishAt - gameTime) / 0.58f);
                paint.setShader(new RadialGradient(x, y, bomb.radius * cell,
                        Color.argb((int) (190 * p), 255, 239, 105),
                        Color.argb(0, 255, 80, 20), Shader.TileMode.CLAMP));
                canvas.drawCircle(x, y, bomb.radius * cell * (1.15f - p * 0.2f), paint);
                paint.setShader(null);
            }
        }
    }

    private void drawEscapeRoute(Canvas canvas) {
        if (!escapeActive || theme == Theme.FACTORY) return;
        float x = gx(escapeX);
        float y = gy(escapeY);
        float pulse = 1f + 0.08f * (float) Math.sin(gameTime * 6f);

        if (theme == Theme.GALAXY) {
            drawWormhole(canvas, x, y, cell * 0.62f * pulse);
            paint.setColor(0x335EDBFF);
            canvas.drawCircle(x, y, cell * 0.92f * pulse, paint);
            drawCenteredText(canvas, "逃生虫洞", x, y - cell * 0.92f,
                    cell * 0.31f, 0xFFE4DDFF);
            return;
        }

        paint.setColor(0x33FFFFFF);
        canvas.drawCircle(x, y, cell * 0.72f * pulse, paint);
        if (theme == Theme.CLOWN) {
            paint.setColor(0xFF6E2B91);
            tmpRect.set(x - cell * 0.48f, y - cell * 0.55f,
                    x + cell * 0.48f, y + cell * 0.55f);
            canvas.drawRoundRect(tmpRect, cell * 0.16f, cell * 0.16f, paint);
            paint.setColor(0xFFFFC857);
            canvas.drawRect(x - cell * 0.08f, y - cell * 0.55f,
                    x + cell * 0.08f, y + cell * 0.55f, paint);
            drawCenteredText(canvas, "EXIT", x, y - cell * 0.67f,
                    cell * 0.28f, Color.WHITE);
        } else if (theme == Theme.CLASSIC) {
            strokePaint.setColor(0xFF62A2FF);
            strokePaint.setStrokeWidth(cell * 0.14f);
            canvas.drawCircle(x, y, cell * 0.54f * pulse, strokePaint);
            Dir arrow = escapeX == 0 ? Dir.LEFT : Dir.RIGHT;
            paint.setColor(0xFFFFE6A4);
            drawTriangle(canvas, x, y, cell * 0.27f, arrow);
            drawCenteredText(canvas, "隧道出口", x, y - cell * 0.72f,
                    cell * 0.27f, Color.WHITE);
        } else if (theme == Theme.OCEAN) {
            paint.setColor(0xFFB7EAF1);
            canvas.drawCircle(x, y, cell * 0.51f, paint);
            paint.setColor(0xFF1B7187);
            canvas.drawCircle(x, y, cell * 0.37f, paint);
            strokePaint.setColor(0xFFE8FFFF);
            strokePaint.setStrokeWidth(cell * 0.08f);
            canvas.drawCircle(x, y, cell * 0.39f, strokePaint);
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3;
                canvas.drawCircle(x + (float) Math.cos(a) * cell * 0.43f,
                        y + (float) Math.sin(a) * cell * 0.43f, cell * 0.055f, paint);
            }
            drawCenteredText(canvas, "救生舱", x, y - cell * 0.72f,
                    cell * 0.28f, 0xFFC9FAFF);
        } else if (theme == Theme.SPRING) {
            paint.setColor(escapeUnlocked ? 0xFFD52331 : 0xFF64212A);
            canvas.drawRect(x - cell * 0.47f, y - cell * 0.50f,
                    x + cell * 0.47f, y + cell * 0.54f, paint);
            paint.setColor(0xFFFFD45B);
            canvas.drawRect(x - cell * 0.55f, y - cell * 0.58f,
                    x + cell * 0.55f, y - cell * 0.42f, paint);
            drawCenteredText(canvas, escapeUnlocked ? "出" : "锁", x,
                    y + cell * 0.19f, cell * 0.50f,
                    escapeUnlocked ? 0xFFFFE9A4 : 0xFFB8A57B);
            drawCenteredText(canvas, escapeUnlocked ? "年门已开" : "年门待开",
                    x, y - cell * 0.72f, cell * 0.27f, Color.WHITE);
        }

        if (theme == Theme.SPRING && gateCrackerArmed && !escapeUnlocked) {
            float fx = gx(gateCrackerX);
            float fy = gy(gateCrackerY);
            drawFirecracker(canvas, fx, fy, cell * 0.42f * pulse, true);
            strokePaint.setColor(0x99FFD45B);
            strokePaint.setStrokeWidth(cell * 0.07f);
            canvas.drawCircle(fx, fy, cell * 1.2f, strokePaint);
            drawCenteredText(canvas, String.valueOf((int) Math.ceil(gateCrackerExplodeAt - gameTime)),
                    fx, fy - cell * 0.68f, cell * 0.38f, Color.WHITE);
        }
    }

    private void drawMeteor(Canvas canvas) {
        if (!meteor.active) return;
        float x = gx(meteor.x);
        float y = gy(meteor.y);
        if (!meteor.impacted) {
            float left = Math.max(0f, meteor.impactAt - gameTime);
            float pulse = 0.75f + 0.25f * (float) Math.sin(gameTime * 15f);
            paint.setColor(Color.argb(55, 255, 70, 70));
            canvas.drawCircle(x, y, cell * 1.55f * pulse, paint);
            strokePaint.setColor(0xFFFF5364);
            strokePaint.setStrokeWidth(cell * 0.11f);
            canvas.drawCircle(x, y, cell * 1.45f, strokePaint);
            drawCenteredText(canvas, "!", x, y + cell * 0.28f, cell * 0.92f, Color.WHITE);
            drawCenteredText(canvas, String.format(Locale.US, "%.1f", left), x,
                    y - cell * 1.15f, cell * 0.33f, 0xFFFFA1A8);
        } else {
            paint.setColor(0xFF482B3B);
            canvas.drawOval(x - cell * 1.3f, y - cell * 0.60f,
                    x + cell * 1.3f, y + cell * 0.60f, paint);
            paint.setColor(0xFF8D655E);
            canvas.drawCircle(x - cell * 0.33f, y - cell * 0.08f, cell * 0.24f, paint);
            canvas.drawCircle(x + cell * 0.42f, y + cell * 0.10f, cell * 0.17f, paint);
            float p = clamp01((meteor.vanishAt - gameTime) / 0.65f);
            paint.setColor(Color.argb((int) (180 * p), 255, 130, 75));
            canvas.drawCircle(x, y, cell * (1.8f - p * 0.5f), paint);
        }
    }

    private void drawBlackHole(Canvas canvas) {
        if (!blackHoleRunning) return;
        int[] core = nearestPassable(COLS / 2, mazeRows / 2);
        float x = gx(core[0]);
        float y = gy(core[1]);
        float activeP = gameTime < blackHoleActiveAt
                ? clamp01((gameTime - blackHoleStart) / (blackHoleActiveAt - blackHoleStart))
                : 1f;
        float r = cell * (0.4f + activeP * 1.45f);
        paint.setShader(new RadialGradient(x, y, r * 1.8f,
                new int[]{0xFF020008, 0xFF351170, 0xAA6F4DFF, 0x0060DFFF},
                new float[]{0f, 0.48f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r * 1.8f, paint);
        paint.setShader(null);
        strokePaint.setStrokeWidth(cell * 0.12f);
        for (int i = 0; i < 3; i++) {
            strokePaint.setColor(Color.argb(160 - i * 35, 150 + i * 25, 100, 255));
            tmpRect.set(x - r * (1f + i * 0.3f), y - r * (0.45f + i * 0.12f),
                    x + r * (1f + i * 0.3f), y + r * (0.45f + i * 0.12f));
            canvas.save();
            canvas.rotate(gameTime * (80f + i * 28f) * (i % 2 == 0 ? 1 : -1), x, y);
            canvas.drawOval(tmpRect, strokePaint);
            canvas.restore();
        }
        if (gameTime < blackHoleActiveAt) {
            drawCenteredText(canvas, blackHoleMode == 0 ? "吸引玩家" : "吸引敌人",
                    x, y - cell * 2.2f, cell * 0.36f, 0xFFE1D8FF);
        }
    }

    private void drawGiantClown(Canvas canvas) {
        float x = gx(12);
        float y = gy(14);
        float s = cell * 1.38f;
        // 肩膀和衣领
        paint.setColor(0xFF7C3CD2);
        canvas.drawOval(x - s * 0.95f, y + s * 0.25f, x + s * 0.95f, y + s * 1.0f, paint);
        paint.setColor(0xFFFFD54F);
        path.reset();
        path.moveTo(x - s * 0.85f, y + s * 0.45f);
        path.lineTo(x - s * 0.35f, y + s * 0.10f);
        path.lineTo(x, y + s * 0.55f);
        path.lineTo(x + s * 0.35f, y + s * 0.10f);
        path.lineTo(x + s * 0.85f, y + s * 0.45f);
        path.close();
        canvas.drawPath(path, paint);
        // 脸与头发
        paint.setColor(0xFFFFF1D7);
        canvas.drawCircle(x, y, s * 0.72f, paint);
        paint.setColor(0xFFFF5A78);
        for (int i = -3; i <= 3; i++) {
            double a = Math.PI * (0.18 + (i + 3) * 0.11);
            canvas.drawCircle(x + (float) Math.cos(a) * s * 0.66f,
                    y - (float) Math.sin(a) * s * 0.63f, s * 0.23f, paint);
        }
        // 帽子
        paint.setColor(0xFF36D6B4);
        path.reset();
        path.moveTo(x - s * 0.50f, y - s * 0.55f);
        path.lineTo(x, y - s * 1.16f);
        path.lineTo(x + s * 0.50f, y - s * 0.55f);
        path.close();
        canvas.drawPath(path, paint);
        paint.setColor(0xFFFFD54F);
        canvas.drawCircle(x, y - s * 1.12f, s * 0.14f, paint);
        // 五官
        paint.setColor(0xFF21223C);
        canvas.drawOval(x - s * 0.36f, y - s * 0.20f, x - s * 0.17f, y + s * 0.04f, paint);
        canvas.drawOval(x + s * 0.17f, y - s * 0.20f, x + s * 0.36f, y + s * 0.04f, paint);
        paint.setColor(0xFFFF3C4F);
        canvas.drawCircle(x, y + s * 0.08f, s * 0.20f, paint);
        strokePaint.setStrokeWidth(s * 0.065f);
        strokePaint.setColor(0xFFB62B4D);
        path.reset();
        path.moveTo(x - s * 0.32f, y + s * 0.35f);
        path.quadTo(x, y + s * 0.63f, x + s * 0.32f, y + s * 0.35f);
        canvas.drawPath(path, strokePaint);

        float elapsed = gameTime - clownAttackStart;
        if (elapsed >= 0f && elapsed < 1.18f) drawClownAttack(canvas, x, y, s, elapsed);
        if (clownWaveStart >= 0f && gameTime - clownWaveStart < 1.2f) {
            float wave = (float) Math.sin((gameTime - clownWaveStart) * 16f) * cell * 0.7f;
            drawClownHand(canvas, x - s * 0.92f - wave, y - s * 0.05f, cell * 0.42f);
            drawClownHand(canvas, x + s * 0.92f + wave, y - s * 0.05f, cell * 0.42f);
        }
    }

    private void drawClownAttack(Canvas canvas, float x, float y, float s, float elapsed) {
        boolean active = elapsed >= 0.68f;
        int color = active ? 0xDDFF334F : 0x66FFB247;
        float left = gx(9) - cell * 0.5f;
        float right = gx(15) + cell * 0.5f;
        float top = gy(11) - cell * 0.5f;
        float bottom = gy(17) + cell * 0.5f;
        paint.setColor(color);
        switch (clownAttackDir) {
            case LEFT: tmpRect.set(left, gy(13) - cell * 0.5f, gx(10) + cell * 0.5f, gy(15) + cell * 0.5f); break;
            case RIGHT: tmpRect.set(gx(14) - cell * 0.5f, gy(13) - cell * 0.5f, right, gy(15) + cell * 0.5f); break;
            case UP: tmpRect.set(gx(11) - cell * 0.5f, top, gx(13) + cell * 0.5f, gy(12) + cell * 0.5f); break;
            case DOWN: tmpRect.set(gx(11) - cell * 0.5f, gy(16) - cell * 0.5f, gx(13) + cell * 0.5f, bottom); break;
            default: return;
        }
        canvas.drawRoundRect(tmpRect, cell * 0.28f, cell * 0.28f, paint);
        float hx = tmpRect.centerX();
        float hy = tmpRect.centerY();
        drawClownHand(canvas, hx, hy, cell * (active ? 0.62f : 0.42f));
    }

    private void drawClownHand(Canvas canvas, float x, float y, float r) {
        paint.setColor(0xFFFFF0D2);
        canvas.drawCircle(x, y, r * 0.65f, paint);
        for (int i = -2; i <= 2; i++) {
            float a = (float) (-Math.PI / 2 + i * 0.25);
            canvas.drawCircle(x + (float) Math.sin(a) * r * 0.55f,
                    y + (float) Math.cos(a) * r * 0.55f, r * 0.23f, paint);
        }
        strokePaint.setStrokeWidth(r * 0.17f);
        strokePaint.setColor(0xFFB43B76);
        canvas.drawCircle(x, y, r * 0.66f, strokePaint);
    }

    private void drawActors(Canvas canvas) {
        if (player != null && player.alive) {
            float alpha = gameTime < invincibleUntil && ((int) (gameTime * 10) & 1) == 0 ? 0.35f : 1f;
            paint.setAlpha((int) (255 * alpha));
            float px = gxFloat(player.x());
            float py = gyFloat(player.y());
            drawSelectedPlayer(canvas, px, py, cell * 0.42f,
                    player.dir == Dir.NONE ? Dir.RIGHT : player.dir);
            paint.setAlpha(255);
            if (theme == Theme.OCEAN && gameTime < bubbleUntil) drawBreathingBubble(canvas, px, py);
            if (gameTime < shieldUntil) drawShield(canvas, px, py);
        }
        if (companion != null && companion.alive) drawCompanion(canvas, companion);

        int[] colors = {0xFFFF4B5C, 0xFFFF7EB7, 0xFF38D6E8, 0xFFFFA23C, 0xFF7EE06B};
        for (Actor enemy : enemies) {
            if (enemy == null || !enemy.alive) continue;
            boolean invisible = gameTime >= enemyInvisibleStart && gameTime < enemyInvisibleUntil && !enemy.revealed;
            if (invisible) continue;
            float x = gxFloat(enemy.x());
            float y = gyFloat(enemy.y());
            boolean frightened = gameTime < powerUntil;
            int color = frightened ? 0xFF345BE8 : colors[enemy.kind];
            if (theme == Theme.OCEAN) drawFish(canvas, x, y, cell * 0.78f, color, frightened, enemy.kind,
                    enemy.dir == Dir.NONE ? (enemy.wanted == Dir.NONE ? Dir.RIGHT : enemy.wanted) : enemy.dir);
            else if (theme == Theme.SPRING) drawLionGhost(canvas, x, y, cell * 0.43f, color, frightened);
            else if (theme == Theme.GALAXY) drawAlienGhost(canvas, x, y, cell * 0.43f, color, frightened);
            else if (theme == Theme.FACTORY) drawRobotEnemy(canvas, x, y, cell * 0.43f, color, frightened, enemy.kind,
                    enemy.dir == Dir.NONE ? enemy.wanted : enemy.dir);
            else drawGhost(canvas, x, y, cell * 0.43f, color, frightened, theme == Theme.CLOWN);
            if (gameTime < enemy.stunnedUntil && !isFactoryEnemyCharging(enemy)) {
                drawStunStars(canvas, x, y - cell * 0.55f);
            }
        }
    }

    private void drawCompanion(Canvas canvas, Actor actor) {
        float x = gxFloat(actor.x());
        float y = gyFloat(actor.y());
        if (theme != Theme.FACTORY) {
            drawExpertAiCompanion(canvas, x, y, cell * 0.40f,
                    actor.dir == Dir.NONE ? Dir.RIGHT : actor.dir);
            return;
        }
        else {
            drawRobotCompanion(canvas, x, y, cell * 0.39f,
                    actor.dir == Dir.NONE ? Dir.RIGHT : actor.dir);
        }
    }

    private void drawTide(Canvas canvas) {
        if (tideLevel <= 0.005f) return;
        float waterTop = originY + mazeRows * cell * (1f - tideLevel);
        paint.setShader(new LinearGradient(0, waterTop, 0, mazeBottom,
                0x5530C4F2, 0xAA00659C, Shader.TileMode.CLAMP));
        canvas.drawRect(originX, waterTop, originX + COLS * cell, mazeBottom, paint);
        paint.setShader(null);
        path.reset();
        path.moveTo(originX, waterTop);
        for (int i = 0; i <= 40; i++) {
            float x = originX + i * COLS * cell / 40f;
            float y = waterTop + (float) Math.sin(i * 0.72f + gameTime * 5f) * cell * 0.23f;
            path.lineTo(x, y);
        }
        strokePaint.setColor(0xCCB8F5FF);
        strokePaint.setStrokeWidth(cell * 0.15f);
        canvas.drawPath(path, strokePaint);
        for (int i = 0; i < 18; i++) {
            float bx = originX + ((starX[i] + gameTime * (0.008f + i * 0.0003f)) % 1f) * COLS * cell;
            float normalized = (starY[i] + gameTime * (0.035f + (i % 4) * 0.006f)) % 1f;
            float by = mazeBottom - normalized * Math.max(cell, mazeBottom - waterTop);
            if (by >= waterTop) {
                strokePaint.setColor(0x99C9F8FF);
                strokePaint.setStrokeWidth(cell * 0.045f);
                canvas.drawCircle(bx, by, cell * (0.08f + i % 3 * 0.025f), strokePaint);
            }
        }
        Actor diver = player != null && player.alive ? player
                : companionOnly && companion != null && companion.alive ? companion : null;
        if (diver != null && isUnderwater(diver.x(), diver.y())) {
            float barW = viewW * 0.36f;
            float y = originY + cell * 0.72f;
            tmpRect.set(viewW * 0.5f - barW / 2f, y, viewW * 0.5f + barW / 2f, y + cell * 0.42f);
            paint.setColor(0xBB071B32);
            canvas.drawRoundRect(tmpRect, cell * 0.18f, cell * 0.18f, paint);
            tmpRect.right = tmpRect.left + barW * clamp01(breath / 5f);
            paint.setColor(breath > 2f ? 0xFF72E6FF : 0xFFFF6B6B);
            canvas.drawRoundRect(tmpRect, cell * 0.18f, cell * 0.18f, paint);
            boolean bubble = gameTime < bubbleUntil;
            int tick = Math.max(0, Math.round((bubble ? bubbleUntil - gameTime : breath) * 10f));
            if (tick != breathLabelTick || bubble != breathLabelBubble) {
                breathLabelTick = tick; breathLabelBubble = bubble;
                breathLabel = (bubble ? "泡泡呼吸 " : "呼吸 ") + (tick / 10) + "." + (tick % 10) + "秒";
            }
            String breathText = breathLabel;
            drawCenteredText(canvas, breathText,
                    viewW * 0.5f, y - cell * 0.10f, cell * 0.31f, Color.WHITE);
        }
    }

    private void drawHud(Canvas canvas) {
        paint.setColor(0xB20A0920);
        canvas.drawRect(0, 0, viewW, Math.max(originY, viewH * 0.052f), paint);
        float size = viewW * 0.030f;
        drawLeftText(canvas, "分数 " + score, viewW * 0.025f, viewH * 0.036f, size, Color.WHITE);
        drawLeftText(canvas, "豆 " + pelletsRemaining, viewW * 0.29f, viewH * 0.036f, size, 0xFFFFD66B);
        drawLeftText(canvas, "命 " + Math.max(0, lives), viewW * 0.49f, viewH * 0.036f, size, 0xFFFF839C);
        if (theme == Theme.OCEAN && gameTime < bubbleUntil) {
            drawLeftText(canvas, "泡泡 " + (int) Math.ceil(bubbleUntil - gameTime) + "秒",
                    viewW * 0.64f, viewH * 0.036f, size * 0.76f, 0xFFBFF8FF);
        } else if (gameTime < heartUntil) {
            drawHeart(canvas, viewW * 0.68f, viewH * 0.027f, viewW * 0.018f, 0xFFFF4D7E);
            drawLeftText(canvas, String.valueOf((int) Math.ceil(heartUntil - gameTime)),
                    viewW * 0.705f, viewH * 0.036f, size * 0.82f, Color.WHITE);
        } else if (companion != null && companion.alive) {
            String aiStatus = companionOnly ? "AI接管" : "AI同行";
            if (companionBombAvailable) {
                aiStatus += theme == Theme.FACTORY ? " · 激光1"
                        : theme == Theme.SPRING ? " · 鞭炮1" : " · 弹1";
            }
            drawLeftText(canvas, aiStatus, viewW * 0.64f,
                    viewH * 0.036f, size * 0.78f, 0xFF67F0C0);
        }
        paint.setColor(0x553C3A6B);
        canvas.drawRoundRect(pauseButton, viewW * 0.015f, viewW * 0.015f, paint);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(viewW * 0.008f);
        float pcx = pauseButton.centerX();
        canvas.drawLine(pcx - viewW * 0.011f, pauseButton.top + viewH * 0.011f,
                pcx - viewW * 0.011f, pauseButton.bottom - viewH * 0.011f, strokePaint);
        canvas.drawLine(pcx + viewW * 0.011f, pauseButton.top + viewH * 0.011f,
                pcx + viewW * 0.011f, pauseButton.bottom - viewH * 0.011f, strokePaint);
    }

    private void drawControls(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(controlsShader);
        canvas.drawRect(0, controlTop, viewW, viewH, paint);
        paint.setShader(null);
        drawCenteredFittedText(canvas, objectiveText(), viewW * 0.5f,
                controlTop + (viewH - controlTop) * 0.045f,
                viewW * 0.0245f, 0xFFE7E5F7, viewW * 0.94f);
        if (companionOnly) {
            drawSpectatorPanel(canvas);
            return;
        }
        paint.setColor(0xFF24213F);
        canvas.drawRoundRect(toggleButton, viewW * 0.025f, viewW * 0.025f, paint);
        drawCenteredText(canvas, autoMoveMode ? "自动移动" : "手动操控", toggleButton.centerX(),
                toggleButton.centerY() + viewW * 0.009f, viewW * 0.025f, 0xFFD6D8F2);

        drawJoystick(canvas);

        float bx = bombButton.centerX();
        float by = bombButton.centerY();
        float br = bombButton.width() / 2f;
        paint.setColor(bombCount > 0 && !companionOnly ? 0xFF493C64 : 0xFF292735);
        canvas.drawCircle(bx, by, br, paint);
        strokePaint.setColor(bombCount > 0 ? 0xFFFFC857 : 0xFF5D5970);
        strokePaint.setStrokeWidth(viewW * 0.007f);
        canvas.drawCircle(bx, by, br, strokePaint);
        String toolLabel;
        if (theme == Theme.FACTORY) {
            paint.setColor(0xFF283E48);
            tmpRect.set(bx - br * 0.34f, by - br * 0.28f, bx + br * 0.34f, by + br * 0.28f);
            canvas.drawRoundRect(tmpRect, br * 0.10f, br * 0.10f, paint);
            strokePaint.setColor(0xFF70EEF2);
            strokePaint.setStrokeWidth(br * 0.09f);
            canvas.drawLine(bx - br * 0.52f, by, bx + br * 0.52f, by, strokePaint);
            canvas.drawLine(bx, by - br * 0.52f, bx, by + br * 0.52f, strokePaint);
            toolLabel = "激光 ×" + bombCount;
        } else if (theme == Theme.SPRING) {
            drawFirecracker(canvas, bx, by - br * 0.08f, br * 0.42f, false);
            toolLabel = "鞭炮 ×" + bombCount;
        } else {
            paint.setColor(0xFF171722);
            canvas.drawCircle(bx, by - br * 0.08f, br * 0.36f, paint);
            strokePaint.setColor(0xFFFFD45B);
            strokePaint.setStrokeWidth(br * 0.11f);
            path.reset();
            path.moveTo(bx + br * 0.20f, by - br * 0.35f);
            path.quadTo(bx + br * 0.42f, by - br * 0.58f, bx + br * 0.53f, by - br * 0.36f);
            canvas.drawPath(path, strokePaint);
            toolLabel = "炸弹 ×" + bombCount;
        }
        drawCenteredText(canvas, toolLabel, bx, by + br * 0.73f, viewW * 0.027f,
                bombCount > 0 ? Color.WHITE : 0xFF777486);
        if (companionOnly) {
            String companionTool = theme == Theme.FACTORY ? "十字激光可用"
                    : theme == Theme.SPRING ? "鞭炮可用" : "炸弹可用";
            drawCenteredText(canvas, companionBombAvailable ? "小跟班自主挑战 · " + companionTool : "小跟班正在自主挑战", viewW * 0.5f,
                    controlTop + (viewH - controlTop) * 0.92f, viewW * 0.032f, 0xFF67F0C0);
        }
    }

    private String objectiveText() {
        if (!escapeActive) {
            switch (theme) {
                case CLOWN: return "任务：收集豆子与五颗小丑球  彩球 " + clownBallsCollected + "/5  剩余 " + pelletsRemaining;
                case CLASSIC: return "任务：吃完全部豆子  剩余 " + pelletsRemaining;
                case OCEAN: return "任务：收集贝壳并躲避潮水  剩余 " + pelletsRemaining
                        + (oceanPearlBoost ? "  珍珠加速 +10%"
                        : oceanPearlPresent ? "  发光珍珠尚未拾取" : "  本局未出现珍珠");
                case SPRING: return "任务：收集全部爆竹豆  剩余 " + pelletsRemaining;
                case GALAXY: return "任务：收集全部小星球  剩余 " + pelletsRemaining;
                case FACTORY: {
                    String timer = factoryCountdownStarted
                            ? "  封锁 " + Math.max(0, (int) Math.ceil(factoryDeadline - gameTime)) + "秒"
                            : "";
                    String maker = companionSummoned ? "" : "  制造 " + Math.min(3, (int) factoryMakerProgress) + "/3秒";
                    String robotState = factoryCharging ? "  分批充电" : factoryReturningToCharge ? "  机器人返厂" : "";
                    return "任务：收集齿轮豆并开启中央大门  剩余 " + pelletsRemaining + maker + robotState + timer;
                }
            }
        }
        if (theme == Theme.GALAXY) return "虫洞正在脚下展开";
        if (theme == Theme.CLASSIC) return escapeX == 0 ? "逃生：穿过左侧隧道出口" : "逃生：穿过右侧隧道出口";
        if (theme == Theme.OCEAN) return "逃生：离开潮水，进入顶部救生舱";
        if (theme == Theme.CLOWN) return "逃生：躲开最终狂欢，进入彩幕门";
        if (theme == Theme.FACTORY) {
            int left = factoryCountdownStarted ? Math.max(0, (int) Math.ceil(factoryDeadline - gameTime)) : -1;
            return left >= 0 ? "逃生：进入中央机械大门  剩余 " + left + " 秒" : "逃生：进入中央机械大门";
        }
        if (!escapeUnlocked) return gateCrackerArmed ? "任务：等待开门爆竹炸响" : "任务：找到金色开门爆竹";
        return "逃生：年门已开启，快进入大门";
    }

    private void drawJoystick(Canvas canvas) {
        paint.setColor(playerJoystickActive ? 0xFF263D54 : 0xFF1A2941);
        canvas.drawCircle(joystickCx, joystickCy, joystickRadius, paint);
        strokePaint.setStrokeWidth(Math.max(1f, viewW * 0.004f));
        strokePaint.setColor(playerJoystickActive ? 0xFF79DFCD : 0xFF435C77);
        canvas.drawCircle(joystickCx, joystickCy, joystickRadius, strokePaint);
        strokePaint.setColor(0x334F8B9B);
        canvas.drawCircle(joystickCx, joystickCy, joystickRadius * 0.72f, strokePaint);
        drawDirectionMarks(canvas, joystickCx, joystickCy, joystickRadius * 0.79f);
        paint.setColor(0x55030912);
        canvas.drawCircle(joystickKnobX, joystickKnobY + joystickRadius * 0.045f, joystickRadius * 0.40f, paint);
        paint.setColor(playerJoystickActive ? 0xFFA9F0DD : 0xFF90ADC6);
        canvas.drawCircle(joystickKnobX, joystickKnobY, joystickRadius * 0.39f, paint);
        paint.setColor(0x50FFFFFF);
        canvas.drawCircle(joystickKnobX - joystickRadius * 0.10f, joystickKnobY - joystickRadius * 0.12f,
                joystickRadius * 0.09f, paint);
    }

    private void drawDirectionMarks(Canvas canvas, float cx, float cy, float r) {
        paint.setColor(0xFFD3D3E5);
        drawTriangle(canvas, cx, cy - r, viewW * 0.018f, Dir.UP);
        drawTriangle(canvas, cx, cy + r, viewW * 0.018f, Dir.DOWN);
        drawTriangle(canvas, cx - r, cy, viewW * 0.018f, Dir.LEFT);
        drawTriangle(canvas, cx + r, cy, viewW * 0.018f, Dir.RIGHT);
    }

    private void drawBanner(Canvas canvas) {
        if (gameTime >= bannerUntil || banner == null || banner.isEmpty()) return;
        float alpha = Math.min(1f, (bannerUntil - gameTime) * 2f);
        textPaint.setTextSize(viewW * 0.031f);
        float width = Math.min(viewW * 0.88f, textPaint.measureText(banner) + viewW * 0.11f);
        tmpRect.set((viewW - width) / 2f, originY + cell * 0.35f,
                (viewW + width) / 2f, originY + cell * 1.65f);
        paint.setColor(Color.argb((int) (205 * alpha), 12, 10, 31));
        canvas.drawRoundRect(tmpRect, cell * 0.42f, cell * 0.42f, paint);
        drawCenteredText(canvas, banner, viewW * 0.5f,
                tmpRect.centerY() + viewW * 0.012f, viewW * 0.031f,
                Color.argb((int) (255 * alpha), 255, 255, 255));
        paint.setAlpha(255);
        textPaint.setAlpha(255);
    }

    private void drawReviveCountdown(Canvas canvas) {
        paint.setColor(0x99100E28);
        canvas.drawCircle(viewW * 0.5f, (mazeTop + mazeBottom) * 0.5f, viewW * 0.15f, paint);
        drawCenteredText(canvas, String.valueOf((int) Math.ceil(Math.max(reviveAt, companionReviveAt) - gameTime)), viewW * 0.5f,
                (mazeTop + mazeBottom) * 0.5f + viewW * 0.045f, viewW * 0.13f, Color.WHITE);
        drawCenteredText(canvas, companionReviveAt > gameTime ? "小跟班复活" : heartRevive ? "爱心复活" : "重新出发", viewW * 0.5f,
                (mazeTop + mazeBottom) * 0.5f + viewW * 0.10f, viewW * 0.028f, 0xFFFF91A6);
    }

    private void drawGameOverlay(Canvas canvas) {
        paint.setColor(0xD20A0918);
        canvas.drawRect(0, 0, viewW, viewH, paint);
        String title;
        String first;
        if (screen == Screen.PAUSED) {
            title = "已暂停";
            first = "继续游戏";
        } else if (screen == Screen.WIN) {
            title = companionOnly ? "小跟班完成通关！" : "迷宫通关！";
            first = "生成新迷宫";
        } else {
            title = companionOnly ? "小跟班也被抓到了" : "挑战结束";
            first = "再来一局";
        }
        drawCenteredText(canvas, title, viewW * 0.5f, viewH * 0.34f,
                viewW * 0.085f, screen == Screen.WIN ? 0xFFFFD45B : Color.WHITE);
        if (screen != Screen.PAUSED) {
            drawCenteredText(canvas, "本局分数 " + score + "   最高分 " + bestScore,
                    viewW * 0.5f, viewH * 0.41f, viewW * 0.034f, 0xFFD0D4F2);
        }
        drawRoundedButton(canvas, resumeButton, first, theme.accent, 0xFF171128);
        drawRoundedButton(canvas, exitButton, "返回地图选择", 0x334D4A6B, Color.WHITE);
    }

    private void drawPacShape(Canvas canvas, float x, float y, float r, Dir dir, int color) {
        float mouth = 19f + 12f * Math.abs((float) Math.sin(gameTime * 8f));
        float base;
        switch (dir) {
            case LEFT: base = 180f; break;
            case UP: base = 270f; break;
            case DOWN: base = 90f; break;
            default: base = 0f; break;
        }
        paint.setColor(color);
        tmpRect.set(x - r, y - r, x + r, y + r);
        canvas.drawArc(tmpRect, base + mouth, 360f - mouth * 2f, true, paint);
        float eyeX = x + (dir == Dir.LEFT ? -0.18f : dir == Dir.RIGHT ? 0.18f : 0.14f) * r;
        float eyeY = y + (dir == Dir.DOWN ? 0.18f : -0.38f) * r;
        paint.setColor(0xFF26202D);
        canvas.drawCircle(eyeX, eyeY, r * 0.095f, paint);
    }

    private void drawGhost(Canvas canvas, float x, float y, float r, int color,
                           boolean frightened, boolean clownStyle) {
        paint.setColor(color);
        // 完整的圆顶与躯干重叠绘制，避免中间出现像“眼镜缝”一样的空白横线。
        canvas.drawCircle(x, y - r * 0.04f, r, paint);
        canvas.drawRect(x - r, y - r * 0.05f, x + r, y + r * 0.68f, paint);
        path.reset();
        path.moveTo(x - r, y + r * 0.62f);
        for (int i = 0; i <= 4; i++) {
            float px = x - r + i * r * 0.5f;
            float py = y + r * (i % 2 == 0 ? 0.93f : 0.62f);
            path.lineTo(px, py);
        }
        path.lineTo(x + r, y);
        path.close();
        canvas.drawPath(path, paint);
        if (frightened) {
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x - r * 0.32f, y - r * 0.05f, r * 0.10f, paint);
            canvas.drawCircle(x + r * 0.32f, y - r * 0.05f, r * 0.10f, paint);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setStrokeWidth(r * 0.10f);
            canvas.drawLine(x - r * 0.36f, y + r * 0.36f, x - r * 0.12f, y + r * 0.22f, strokePaint);
            canvas.drawLine(x - r * 0.12f, y + r * 0.22f, x + r * 0.12f, y + r * 0.36f, strokePaint);
            canvas.drawLine(x + r * 0.12f, y + r * 0.36f, x + r * 0.36f, y + r * 0.22f, strokePaint);
            return;
        }
        paint.setColor(Color.WHITE);
        canvas.drawOval(x - r * 0.58f, y - r * 0.40f, x - r * 0.10f, y + r * 0.12f, paint);
        canvas.drawOval(x + r * 0.10f, y - r * 0.40f, x + r * 0.58f, y + r * 0.12f, paint);
        paint.setColor(0xFF24336B);
        canvas.drawCircle(x - r * 0.28f, y - r * 0.10f, r * 0.13f, paint);
        canvas.drawCircle(x + r * 0.40f, y - r * 0.10f, r * 0.13f, paint);
        if (clownStyle) {
            paint.setColor(0xFFFFE044);
            canvas.drawCircle(x, y + r * 0.22f, r * 0.16f, paint);
            path.reset();
            path.moveTo(x - r * 0.55f, y - r * 0.62f);
            path.lineTo(x, y - r * 1.12f);
            path.lineTo(x + r * 0.55f, y - r * 0.62f);
            path.close();
            paint.setColor(0xFF56E0BF);
            canvas.drawPath(path, paint);
        }
    }

    private void drawFish(Canvas canvas, float x, float y, float size, int color,
                          boolean frightened, int kind, Dir direction) {
        float angle;
        switch (direction) {
            case LEFT: angle = 180f; break;
            case UP: angle = -90f; break;
            case DOWN: angle = 90f; break;
            default: angle = 0f; break;
        }
        float r = size * 0.52f;
        float swim = (float) Math.sin(gameTime * 9f + kind * 1.7f);
        canvas.save();
        canvas.rotate(angle, x, y);
        paint.setColor(frightened ? 0xFF345BE8 : color);
        canvas.drawOval(x - r, y - r * 0.58f, x + r, y + r * 0.58f, paint);

        // 尾巴会轻微摆动，转向时整条鱼会随移动方向旋转。
        float tailWave = swim * r * 0.16f;
        path.reset();
        path.moveTo(x - r * 0.82f, y);
        path.lineTo(x - r * 1.55f, y - r * 0.72f + tailWave);
        path.lineTo(x - r * 1.55f, y + r * 0.72f + tailWave);
        path.close();
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(x, y - r * 0.42f);
        path.lineTo(x - r * 0.24f, y - r * 0.93f - swim * r * 0.05f);
        path.lineTo(x + r * 0.34f, y - r * 0.48f);
        path.close();
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(x - r * 0.12f, y + r * 0.40f);
        path.lineTo(x - r * 0.38f, y + r * 0.82f + swim * r * 0.04f);
        path.lineTo(x + r * 0.30f, y + r * 0.48f);
        path.close();
        paint.setColor(mixColor(frightened ? 0xFF345BE8 : color, Color.WHITE, 0.10f));
        canvas.drawPath(path, paint);

        paint.setColor(Color.WHITE);
        canvas.drawCircle(x + r * 0.52f, y - r * 0.16f, r * 0.17f, paint);
        paint.setColor(frightened ? 0xFFFFE064 : 0xFF142B4D);
        canvas.drawCircle(x + r * 0.58f, y - r * 0.16f, r * 0.075f, paint);
        strokePaint.setStrokeWidth(r * 0.10f);
        strokePaint.setColor(0xAAFFFFFF);
        for (int i = -1; i <= 1; i++) {
            float sx = x + i * r * 0.35f - r * 0.15f;
            canvas.drawLine(sx, y - r * 0.44f, sx - r * 0.15f, y + r * 0.44f, strokePaint);
        }
        if (kind == 0 && !frightened) {
            paint.setColor(0xFFFFFFFF);
            path.reset();
            path.moveTo(x + r * 0.85f, y + r * 0.10f);
            path.lineTo(x + r * 0.55f, y + r * 0.24f);
            path.lineTo(x + r * 0.82f, y + r * 0.33f);
            path.close();
            canvas.drawPath(path, paint);
        }
        canvas.restore();
    }

    private void drawLionGhost(Canvas canvas, float x, float y, float r, int color, boolean frightened) {
        paint.setColor(frightened ? 0xFF385EDC : color);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            canvas.drawCircle(x + (float) Math.cos(a) * r * 0.65f,
                    y + (float) Math.sin(a) * r * 0.65f, r * 0.35f, paint);
        }
        paint.setColor(0xFFFFD66B);
        canvas.drawCircle(x, y, r * 0.72f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x - r * 0.27f, y - r * 0.13f, r * 0.20f, paint);
        canvas.drawCircle(x + r * 0.27f, y - r * 0.13f, r * 0.20f, paint);
        paint.setColor(0xFF22202C);
        canvas.drawCircle(x - r * 0.24f, y - r * 0.10f, r * 0.08f, paint);
        canvas.drawCircle(x + r * 0.30f, y - r * 0.10f, r * 0.08f, paint);
        paint.setColor(0xFFFF4D51);
        canvas.drawCircle(x, y + r * 0.20f, r * 0.16f, paint);
        strokePaint.setColor(0xFF702A27);
        strokePaint.setStrokeWidth(r * 0.09f);
        canvas.drawLine(x - r * 0.28f, y + r * 0.42f, x + r * 0.28f, y + r * 0.42f, strokePaint);
    }

    private void drawAlienGhost(Canvas canvas, float x, float y, float r, int color, boolean frightened) {
        paint.setShader(new RadialGradient(x - r * 0.2f, y - r * 0.25f, r * 1.2f,
                frightened ? 0xFF5E78FF : mixColor(color, Color.WHITE, 0.35f),
                frightened ? 0xFF2639A9 : color, Shader.TileMode.CLAMP));
        canvas.drawOval(x - r, y - r * 0.78f, x + r, y + r * 0.86f, paint);
        paint.setShader(null);
        paint.setColor(0xFF08112D);
        canvas.drawOval(x - r * 0.52f, y - r * 0.28f, x - r * 0.08f, y + r * 0.12f, paint);
        canvas.drawOval(x + r * 0.08f, y - r * 0.28f, x + r * 0.52f, y + r * 0.12f, paint);
        strokePaint.setColor(0xAAE1D8FF);
        strokePaint.setStrokeWidth(r * 0.08f);
        canvas.drawCircle(x, y, r * 1.05f, strokePaint);
        canvas.drawCircle(x, y, r * 1.25f + (float) Math.sin(gameTime * 5f) * r * 0.08f, strokePaint);
    }

    private void drawStunStars(Canvas canvas, float x, float y) {
        for (int i = 0; i < 3; i++) {
            double a = gameTime * 4.5 + i * Math.PI * 2 / 3;
            drawStar(canvas, x + (float) Math.cos(a) * cell * 0.36f,
                    y + (float) Math.sin(a) * cell * 0.13f,
                    cell * 0.10f, 0xFFFFE15C);
        }
    }

    private void drawCarnivalDot(Canvas canvas, float x, float y, float r, int color) {
        paint.setColor(color);
        canvas.drawCircle(x, y, r * 0.62f, paint);
        paint.setColor(0x88FFFFFF);
        canvas.drawCircle(x - r * 0.18f, y - r * 0.18f, r * 0.18f, paint);
    }

    private void drawClownBall(Canvas canvas, float x, float y, float r, int color, boolean power) {
        paint.setColor(color);
        canvas.drawCircle(x, y, r, paint);
        strokePaint.setColor(power ? Color.WHITE : 0xAAFFFFFF);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.20f));
        tmpRect.set(x - r * 0.78f, y - r * 0.42f, x + r * 0.78f, y + r * 0.42f);
        canvas.save();
        canvas.rotate(-28f, x, y);
        canvas.drawArc(tmpRect, 190f, 160f, false, strokePaint);
        canvas.restore();
        paint.setColor(0xEEFFFFFF);
        canvas.drawCircle(x - r * 0.27f, y - r * 0.18f, r * 0.13f, paint);
        canvas.drawCircle(x + r * 0.27f, y - r * 0.18f, r * 0.13f, paint);
        paint.setColor(0xFF26304A);
        canvas.drawCircle(x - r * 0.25f, y - r * 0.17f, r * 0.055f, paint);
        canvas.drawCircle(x + r * 0.25f, y - r * 0.17f, r * 0.055f, paint);
        paint.setColor(0xFFFF3F55);
        canvas.drawCircle(x, y + r * 0.06f, r * 0.17f, paint);
        if (power) {
            strokePaint.setColor(0xCCFFFFFF);
            strokePaint.setStrokeWidth(Math.max(1f, r * 0.10f));
            canvas.drawCircle(x, y, r * 1.18f, strokePaint);
        }
    }

    private void drawStar(Canvas canvas, float x, float y, float r, int color) {
        paint.setColor(color);
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = (i & 1) == 0 ? r : r * 0.43f;
            float px = x + (float) Math.cos(a) * rr;
            float py = y + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawPlanet(Canvas canvas, float x, float y, float r, int color) {
        strokePaint.setColor(mixColor(color, Color.WHITE, 0.35f));
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.22f));
        tmpRect.set(x - r * 1.35f, y - r * 0.48f, x + r * 1.35f, y + r * 0.48f);
        canvas.save();
        canvas.rotate(-18f, x, y);
        canvas.drawOval(tmpRect, strokePaint);
        canvas.restore();
        paint.setColor(color);
        canvas.drawCircle(x, y, r, paint);
        paint.setColor(0x55FFFFFF);
        canvas.drawCircle(x - r * 0.28f, y - r * 0.30f, r * 0.27f, paint);
    }

    private void drawPearl(Canvas canvas, float x, float y, float r) {
        float pulse = 0.92f + 0.10f * (float) Math.sin(gameTime * 5.5f);
        paint.setShader(new RadialGradient(x, y, r * 2.2f,
                0xAAE8FFFF, 0x0069E7FF, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r * 2.2f, paint);
        paint.setShader(null);
        paint.setColor(0xFFF4FFFF);
        canvas.drawCircle(x, y, r * 0.78f * pulse, paint);
        paint.setColor(0xFFBCEEFF);
        canvas.drawCircle(x + r * 0.16f, y + r * 0.12f, r * 0.56f * pulse, paint);
        paint.setColor(0xCCFFFFFF);
        canvas.drawCircle(x - r * 0.22f, y - r * 0.24f, r * 0.20f, paint);
        strokePaint.setColor(0xFF7FE5FF);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.10f));
        canvas.drawCircle(x, y, r * 0.86f * pulse, strokePaint);
    }

    private void drawBreathingBubble(Canvas canvas, float x, float y) {
        float pulse = 1f + 0.035f * (float) Math.sin(gameTime * 6f);
        paint.setColor(0x225DEBFF);
        canvas.drawCircle(x, y, cell * 0.57f * pulse, paint);
        strokePaint.setColor(0xCCE0FFFF);
        strokePaint.setStrokeWidth(cell * 0.055f);
        canvas.drawCircle(x, y, cell * 0.54f * pulse, strokePaint);
        paint.setColor(0x99FFFFFF);
        canvas.drawCircle(x - cell * 0.19f, y - cell * 0.23f, cell * 0.075f, paint);
    }

    private void drawSelectedPlayer(Canvas canvas, float x, float y, float r, Dir dir) {
        if (playerStyle == 1) drawYellowBird(canvas, x, y, r, dir);
        else drawPacShape(canvas, x, y, r, dir, 0xFFFFD43B);
    }

    private void drawPlayerStylePreview(Canvas canvas, int style, float x, float y, float r) {
        int previous = playerStyle;
        playerStyle = style;
        drawSelectedPlayer(canvas, x, y, r, Dir.RIGHT);
        playerStyle = previous;
    }

    private void drawYellowBird(Canvas canvas, float x, float y, float r, Dir dir) {
        float angle = dir == Dir.UP ? -90f : dir == Dir.DOWN ? 90f : 0f;
        float sx = dir == Dir.LEFT ? -1f : 1f;
        float wing = 0.5f + 0.5f * (float) Math.sin(gameTime * 13f);
        canvas.save();
        canvas.rotate(angle, x, y);

        int outline = 0xFF513515;
        int bodyLight = 0xFFFFEA68;
        int bodyDark = 0xFFF0A928;
        int wingColor = 0xFFF6C54D;
        paint.setAlpha(255);
        strokePaint.setAlpha(255);

        // 尾羽：严格照搬最新“小鸟小鸟”文件里的两片尾羽。
        // 先画尾羽、再画身体，让身体遮住尾根，只露出后方完整尾羽。
        strokePaint.setColor(outline);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.10f));
        paint.setColor(bodyDark);
        path.reset();
        path.moveTo(x - sx * r * 0.88f, y - r * 0.10f);
        path.lineTo(x - sx * r * 1.78f, y - r * 0.28f);
        path.quadTo(x - sx * r * 1.48f, y + r * 0.03f,
                x - sx * r * 0.92f, y + r * 0.16f);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawPath(path, strokePaint);

        path.reset();
        path.moveTo(x - sx * r * 0.94f, y + r * 0.06f);
        path.lineTo(x - sx * r * 1.72f, y + r * 0.43f);
        path.quadTo(x - sx * r * 1.38f, y + r * 0.48f,
                x - sx * r * 0.84f, y + r * 0.28f);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawPath(path, strokePaint);

        // 身体
        strokePaint.setColor(outline);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.12f));
        paint.setShader(new RadialGradient(x - sx * r * 0.38f, y - r * 0.38f,
                r * 1.75f, bodyLight, bodyDark, Shader.TileMode.CLAMP));
        if (sx > 0f) {
            canvas.drawOval(x - r * 1.20f, y - r * 0.88f,
                    x + r * 1.13f, y + r * 0.92f, paint);
            paint.setShader(null);
            canvas.drawOval(x - r * 1.20f, y - r * 0.88f,
                    x + r * 1.13f, y + r * 0.92f, strokePaint);
        } else {
            canvas.drawOval(x - r * 1.13f, y - r * 0.88f,
                    x + r * 1.20f, y + r * 0.92f, paint);
            paint.setShader(null);
            canvas.drawOval(x - r * 1.13f, y - r * 0.88f,
                    x + r * 1.20f, y + r * 0.92f, strokePaint);
        }

        // 翅膀
        paint.setColor(wingColor);
        path.reset();
        path.moveTo(x - sx * r * 0.15f, y + r * 0.08f);
        path.quadTo(x - sx * r * 0.98f, y + r * (0.30f + wing * 0.28f),
                x - sx * r * 0.88f, y + r * (0.70f + wing * 0.10f));
        path.quadTo(x - sx * r * 0.24f, y + r * 0.70f,
                x + sx * r * 0.18f, y + r * 0.26f);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawPath(path, strokePaint);

        // 眼睛
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x + sx * r * 0.46f, y - r * 0.30f, r * 0.48f, paint);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.09f));
        canvas.drawCircle(x + sx * r * 0.46f, y - r * 0.30f, r * 0.48f, strokePaint);
        paint.setColor(0xFF1F2933);
        canvas.drawCircle(x + sx * r * 0.60f, y - r * 0.27f, r * 0.18f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x + sx * r * 0.66f, y - r * 0.34f, r * 0.055f, paint);

        // 嘴巴：严格参考“小鸟小鸟”文件的橙色三角嘴与中线画法。
        paint.setColor(0xFFFF713C);
        path.reset();
        path.moveTo(x + sx * r * 0.82f, y - r * 0.04f);
        path.lineTo(x + sx * r * 1.72f, y + r * 0.16f);
        path.lineTo(x + sx * r * 0.82f, y + r * 0.32f);
        path.close();
        canvas.drawPath(path, paint);
        strokePaint.setColor(0xFF7A381B);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.09f));
        canvas.drawPath(path, strokePaint);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.06f));
        canvas.drawLine(x + sx * r * 0.88f, y + r * 0.16f,
                x + sx * r * 1.55f, y + r * 0.16f, strokePaint);

        canvas.restore();
    }

    private void drawFairyWand(Canvas canvas, float x, float y, float r) {
        canvas.save();
        canvas.rotate(-28f, x, y);
        strokePaint.setColor(0xFFFFE7A0);
        strokePaint.setStrokeWidth(r * 0.22f);
        canvas.drawLine(x, y + r * 0.90f, x, y - r * 0.10f, strokePaint);
        paint.setColor(0xFFFF78B6);
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? r * 0.64f : r * 0.28f;
            float px = x + (float) Math.cos(a) * rr;
            float py = y - r * 0.46f + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        canvas.drawPath(path, paint);
        paint.setColor(0xAAFFFFFF);
        for (int i = 0; i < 4; i++) {
            float a = gameTime * 2f + i * 1.57f;
            canvas.drawCircle(x + (float) Math.cos(a) * r * 0.95f,
                    y - r * 0.42f + (float) Math.sin(a) * r * 0.95f, r * 0.09f, paint);
        }
        canvas.restore();
    }

    private void drawSpringDragonBackdrop(Canvas canvas) {
        float span = viewW + cell * 8f;
        float x = -cell * 4f + (gameTime * viewW * 0.045f) % span;
        float y = controlTop + (viewH - controlTop) * 0.18f
                + (float) Math.sin(gameTime * 1.1f) * cell * 0.35f;
        drawDragon(canvas, x, y, cell * 0.55f, 72, Dir.RIGHT);
    }

    private void drawSpringDragonEntity(Canvas canvas) {
        if (!dragonDashing) return;
        float x = gxFloat(dragonCurrentX);
        float y = gyFloat(dragonCurrentY);
        float angle = (float) Math.atan2(dragonTargetY - dragonStartY,
                dragonTargetX - dragonStartX);
        Dir direction = Math.abs(Math.cos(angle)) >= Math.abs(Math.sin(angle))
                ? (Math.cos(angle) >= 0 ? Dir.RIGHT : Dir.LEFT)
                : (Math.sin(angle) >= 0 ? Dir.DOWN : Dir.UP);
        if (gameTime < dragonMoveStart) {
            float pulse = 0.85f + 0.15f * (float) Math.sin(gameTime * 14f);
            paint.setColor(0x44FF5548);
            canvas.drawCircle(x, y, cell * 1.15f * pulse, paint);
        }
        drawDragon(canvas, x, y, cell * 0.78f, 255, direction);
    }

    private void drawDragon(Canvas canvas, float x, float y, float scale, int alpha, Dir dir) {
        float angle = dir == Dir.UP ? -90f : dir == Dir.DOWN ? 90f : dir == Dir.LEFT ? 180f : 0f;
        canvas.save();
        canvas.rotate(angle, x, y);
        for (int i = 7; i >= 0; i--) {
            float sx = x - i * scale * 0.48f;
            float sy = y + (float) Math.sin(gameTime * 6f - i * 0.75f) * scale * 0.22f;
            int a = Math.max(24, alpha - i * 12);
            paint.setColor(Color.argb(a, 210, 42, 50));
            canvas.drawCircle(sx, sy, scale * (0.30f + (7 - i) * 0.012f), paint);
            paint.setColor(Color.argb(a, 255, 214, 79));
            canvas.drawCircle(sx, sy - scale * 0.20f, scale * 0.075f, paint);
        }
        paint.setColor(Color.argb(alpha, 229, 48, 55));
        canvas.drawOval(x - scale * 0.18f, y - scale * 0.42f,
                x + scale * 0.78f, y + scale * 0.42f, paint);
        paint.setColor(Color.argb(alpha, 255, 218, 82));
        path.reset();
        path.moveTo(x + scale * 0.62f, y - scale * 0.28f);
        path.lineTo(x + scale * 1.02f, y - scale * 0.52f);
        path.lineTo(x + scale * 0.78f, y - scale * 0.05f);
        path.close();
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(x + scale * 0.62f, y + scale * 0.28f);
        path.lineTo(x + scale * 1.02f, y + scale * 0.52f);
        path.lineTo(x + scale * 0.78f, y + scale * 0.05f);
        path.close();
        canvas.drawPath(path, paint);
        paint.setColor(Color.argb(alpha, 70, 22, 28));
        canvas.drawCircle(x + scale * 0.55f, y - scale * 0.15f, scale * 0.075f, paint);
        canvas.drawCircle(x + scale * 0.55f, y + scale * 0.15f, scale * 0.075f, paint);
        canvas.restore();
    }

    private void drawShell(Canvas canvas, float x, float y, float r, boolean power) {
        paint.setColor(power ? 0xFFFFE8BE : 0xFFFFB4B4);
        tmpRect.set(x - r, y - r * 0.75f, x + r, y + r * 0.90f);
        canvas.drawArc(tmpRect, 180f, 180f, true, paint);
        canvas.drawRect(x - r, y, x + r, y + r * 0.55f, paint);
        strokePaint.setColor(power ? 0xFFFF9D70 : 0xFFC66F86);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.10f));
        for (int i = -2; i <= 2; i++) {
            canvas.drawLine(x, y + r * 0.45f, x + i * r * 0.40f, y - r * 0.48f, strokePaint);
        }
    }

    private void drawFirecracker(Canvas canvas, float x, float y, float r, boolean power) {
        canvas.save();
        canvas.rotate(-18f, x, y);
        paint.setColor(0xFFD91E36);
        tmpRect.set(x - r * 0.48f, y - r, x + r * 0.48f, y + r);
        canvas.drawRoundRect(tmpRect, r * 0.18f, r * 0.18f, paint);
        paint.setColor(0xFFFFD05C);
        canvas.drawRect(x - r * 0.52f, y - r, x + r * 0.52f, y - r * 0.69f, paint);
        canvas.drawRect(x - r * 0.52f, y + r * 0.69f, x + r * 0.52f, y + r, paint);
        if (power) {
            strokePaint.setColor(0xFFFFD65A);
            strokePaint.setStrokeWidth(Math.max(1f, r * 0.16f));
            path.reset();
            path.moveTo(x, y - r);
            path.quadTo(x + r * 0.52f, y - r * 1.38f, x + r * 0.75f, y - r * 1.10f);
            canvas.drawPath(path, strokePaint);
        }
        canvas.restore();
    }

    private void drawHeart(Canvas canvas, float x, float y, float r, int color) {
        paint.setColor(color);
        path.reset();
        path.moveTo(x, y + r * 0.95f);
        path.cubicTo(x - r * 1.35f, y + r * 0.10f, x - r * 0.95f, y - r * 0.85f, x, y - r * 0.30f);
        path.cubicTo(x + r * 0.95f, y - r * 0.85f, x + r * 1.35f, y + r * 0.10f, x, y + r * 0.95f);
        canvas.drawPath(path, paint);
    }

    private void drawGift(Canvas canvas, float x, float y, float r) {
        paint.setColor(0xFFFF4E87);
        tmpRect.set(x - r, y - r * 0.72f, x + r, y + r);
        canvas.drawRoundRect(tmpRect, r * 0.18f, r * 0.18f, paint);
        paint.setColor(0xFFFFD45D);
        canvas.drawRect(x - r * 0.13f, y - r * 0.72f, x + r * 0.13f, y + r, paint);
        canvas.drawRect(x - r, y - r * 0.22f, x + r, y + r * 0.06f, paint);
        strokePaint.setColor(0xFFFFD45D);
        strokePaint.setStrokeWidth(r * 0.20f);
        canvas.drawOval(x - r * 0.65f, y - r * 1.12f, x, y - r * 0.42f, strokePaint);
        canvas.drawOval(x, y - r * 1.12f, x + r * 0.65f, y - r * 0.42f, strokePaint);
    }

    private void drawWormhole(Canvas canvas, float x, float y, float r) {
        for (int i = 4; i >= 0; i--) {
            float p = i / 4f;
            strokePaint.setStrokeWidth(Math.max(1f, r * (0.12f + p * 0.05f)));
            strokePaint.setColor(Color.argb(210 - i * 25, 100 + i * 28, 100, 255));
            tmpRect.set(x - r * (1f - p * 0.14f), y - r * (0.45f + p * 0.08f),
                    x + r * (1f - p * 0.14f), y + r * (0.45f + p * 0.08f));
            canvas.save();
            canvas.rotate(gameTime * 90f + i * 18f, x, y);
            canvas.drawOval(tmpRect, strokePaint);
            canvas.restore();
        }
        paint.setColor(0xFF05010D);
        canvas.drawCircle(x, y, r * 0.33f, paint);
    }

    private void drawShield(Canvas canvas, float x, float y) {
        float r = cell * 0.57f;
        paint.setColor(0x334DE7FF);
        canvas.drawCircle(x, y, r, paint);
        strokePaint.setColor(0xCC80EDFF);
        strokePaint.setStrokeWidth(cell * 0.075f);
        canvas.drawCircle(x, y, r, strokePaint);
    }

    private void drawBalloon(Canvas canvas, float x, float y, float r, int color) {
        paint.setColor(color);
        canvas.drawOval(x - r * 0.75f, y - r, x + r * 0.75f, y + r, paint);
        path.reset();
        path.moveTo(x - r * 0.16f, y + r);
        path.lineTo(x + r * 0.16f, y + r);
        path.lineTo(x, y + r * 1.25f);
        path.close();
        canvas.drawPath(path, paint);
        strokePaint.setColor(0x99FFFFFF);
        strokePaint.setStrokeWidth(Math.max(1f, r * 0.06f));
        path.reset();
        path.moveTo(x, y + r * 1.25f);
        path.quadTo(x + r * 0.42f, y + r * 1.75f, x, y + r * 2.15f);
        canvas.drawPath(path, strokePaint);
    }

    private void drawFireworks(Canvas canvas) {
        float[][] centers = {{0.18f, 0.16f}, {0.78f, 0.22f}, {0.58f, 0.09f}};
        int[] colors = {0x55FFD65A, 0x55FF6B88, 0x5580E8FF};
        for (int k = 0; k < centers.length; k++) {
            float cx = viewW * centers[k][0];
            float cy = mazeTop + (mazeBottom - mazeTop) * centers[k][1];
            float radius = cell * (1.1f + 0.25f * (float) Math.sin(gameTime * 2f + k));
            strokePaint.setColor(colors[k]);
            strokePaint.setStrokeWidth(cell * 0.055f);
            for (int i = 0; i < 10; i++) {
                double a = i * Math.PI / 5 + k;
                canvas.drawLine(cx + (float) Math.cos(a) * radius * 0.35f,
                        cy + (float) Math.sin(a) * radius * 0.35f,
                        cx + (float) Math.cos(a) * radius,
                        cy + (float) Math.sin(a) * radius, strokePaint);
            }
        }
    }

    private void drawMiniClown(Canvas canvas, float x, float y, float r) {
        paint.setColor(0xFFFFF0D5);
        canvas.drawCircle(x, y, r, paint);
        paint.setColor(0xFFFF5A78);
        canvas.drawCircle(x - r * 0.80f, y - r * 0.25f, r * 0.42f, paint);
        canvas.drawCircle(x + r * 0.80f, y - r * 0.25f, r * 0.42f, paint);
        paint.setColor(0xFFFF3D4F);
        canvas.drawCircle(x, y + r * 0.08f, r * 0.24f, paint);
        paint.setColor(0xFF21223C);
        canvas.drawCircle(x - r * 0.33f, y - r * 0.20f, r * 0.10f, paint);
        canvas.drawCircle(x + r * 0.33f, y - r * 0.20f, r * 0.10f, paint);
        paint.setColor(0xFF53D8B5);
        path.reset();
        path.moveTo(x - r * 0.55f, y - r * 0.67f);
        path.lineTo(x, y - r * 1.35f);
        path.lineTo(x + r * 0.55f, y - r * 0.67f);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawTriangle(Canvas canvas, float x, float y, float r, Dir dir) {
        path.reset();
        if (dir == Dir.UP || dir == Dir.DOWN) {
            float sign = dir == Dir.UP ? -1f : 1f;
            path.moveTo(x, y + sign * r);
            path.lineTo(x - r, y - sign * r * 0.7f);
            path.lineTo(x + r, y - sign * r * 0.7f);
        } else {
            float sign = dir == Dir.LEFT ? -1f : 1f;
            path.moveTo(x + sign * r, y);
            path.lineTo(x - sign * r * 0.7f, y - r);
            path.lineTo(x - sign * r * 0.7f, y + r);
        }
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawRoundedButton(Canvas canvas, RectF rect, String text, int bg, int fg) {
        paint.setColor(bg);
        canvas.drawRoundRect(rect, rect.height() * 0.36f, rect.height() * 0.36f, paint);
        drawCenteredText(canvas, text, rect.centerX(), rect.centerY() + rect.height() * 0.12f,
                Math.min(viewW * 0.041f, rect.height() * 0.43f), fg);
    }

    private void drawBackButton(Canvas canvas, String label) {
        paint.setColor(0x443F3D61);
        canvas.drawRoundRect(backButton, viewW * 0.018f, viewW * 0.018f, paint);
        drawCenteredText(canvas, "‹  " + label, backButton.centerX(),
                backButton.centerY() + viewW * 0.009f, viewW * 0.029f, Color.WHITE);
    }

    private void drawCenteredText(Canvas canvas, String text, float x, float baseline,
                                  float size, int color) {
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        canvas.drawText(text, x, baseline, textPaint);
    }

    private void drawCenteredFittedText(Canvas canvas, String text, float x, float baseline,
                                        float size, int color, float maxWidth) {
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(size);
        float measured = textPaint.measureText(text);
        if (measured > maxWidth && measured > 0f) {
            textPaint.setTextSize(size * maxWidth / measured);
        }
        textPaint.setColor(color);
        canvas.drawText(text, x, baseline, textPaint);
    }

    private void drawLeftFittedText(Canvas canvas, String text, float x, float baseline,
                                    float size, int color, float maxWidth) {
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(size);
        float measured = textPaint.measureText(text);
        if (measured > maxWidth && measured > 0f) textPaint.setTextSize(size * maxWidth / measured);
        textPaint.setColor(color);
        canvas.drawText(text, x, baseline, textPaint);
    }

    private void drawLeftText(Canvas canvas, String text, float x, float baseline,
                              float size, int color) {
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(size);
        float maxWidth = viewW - x - viewW * 0.05f;
        float measured = textPaint.measureText(text);
        if (measured > maxWidth && measured > 0f) textPaint.setTextSize(size * maxWidth / measured);
        textPaint.setColor(color);
        canvas.drawText(text, x, baseline, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (guardedError != null && action == MotionEvent.ACTION_DOWN) {
            guardedError = null; screen = Screen.HOME; lastFrameNanos = 0L;
            resetJoystick(); invalidate(); return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            resetJoystick();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            requestUnbufferedDispatch(event);
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            // Additional fingers can use a weapon, but cannot click through a menu transition.
            if (action == MotionEvent.ACTION_DOWN || screen == Screen.PLAYING) {
                handleTouchDown(event.getX(index), event.getY(index), event.getPointerId(index));
            }
        } else if (action == MotionEvent.ACTION_MOVE && joystickPointer >= 0) {
            int active = event.findPointerIndex(joystickPointer);
            if (active >= 0) updateJoystickInput(event.getX(active), event.getY(active));
            else resetJoystick();
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP) {
            if (action != MotionEvent.ACTION_POINTER_UP || event.getPointerId(index) == joystickPointer) {
                resetJoystick(); postInvalidateOnAnimation();
            }
            if (action != MotionEvent.ACTION_POINTER_UP && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() { super.performClick(); return true; }

    private void handleTouchDown(float x, float y, int pointerId) {
        switch (screen) {
            case HOME:
                if (startButton.contains(x, y)) {
                    screen = Screen.MAP_SELECT;
                    playTone(ToneGenerator.TONE_PROP_BEEP2, 65);
                    invalidate();
                } else if (guideButton.contains(x, y)) {
                    screenBeforeGuide = Screen.HOME;
                    screen = Screen.GUIDE;
                    invalidate();
                }
                return;
            case MAP_SELECT:
                if (backButton.contains(x, y)) {
                    screen = Screen.HOME;
                    invalidate();
                    return;
                }
                if (lifeMinusButton.contains(x, y)) {
                    configuredLives = Math.max(1, configuredLives - 1);
                    prefs.edit().putInt("生命设置", configuredLives).apply();
                    invalidate();
                    return;
                }
                if (lifePlusButton.contains(x, y)) {
                    configuredLives = Math.min(5, configuredLives + 1);
                    prefs.edit().putInt("生命设置", configuredLives).apply();
                    invalidate();
                    return;
                }
                if (difficultyButton.contains(x, y)) {
                    difficulty = Difficulty.values()[(difficulty.ordinal() + 1) % Difficulty.values().length];
                    prefs.edit().putInt("难度设置", difficulty.ordinal()).apply();
                    invalidate();
                    return;
                }
                for (int i = 0; i < playerStyleButtons.length; i++) {
                    if (playerStyleButtons[i].contains(x, y)) {
                        playerStyle = i;
                        prefs.edit().putInt("玩家形象", playerStyle).apply();
                        playTone(ToneGenerator.TONE_PROP_BEEP, 55);
                        invalidate();
                        return;
                    }
                }
                for (int i = 0; i < mapCards.length; i++) {
                    if (mapCards[i].contains(x, y)) {
                        startGame(Theme.values()[i]);
                        return;
                    }
                }
                return;
            case GUIDE:
                if (backButton.contains(x, y)) {
                    screen = screenBeforeGuide;
                    invalidate();
                }
                return;
            case PAUSED:
                if (resumeButton.contains(x, y)) {
                    screen = Screen.PLAYING;
                    lastFrameNanos = 0L;
                    invalidate();
                } else if (exitButton.contains(x, y)) {
                    screen = Screen.MAP_SELECT;
                    invalidate();
                }
                return;
            case GAME_OVER:
            case WIN:
                if (resumeButton.contains(x, y)) startGame(theme);
                else if (exitButton.contains(x, y)) {
                    screen = Screen.MAP_SELECT;
                    invalidate();
                }
                return;
            case PLAYING:
                break;
        }

        if (pauseButton.contains(x, y)) {
            screen = Screen.PAUSED;
            resetJoystick();
            joystickPointer = -1;
            joystickKnobX = joystickCx;
            joystickKnobY = joystickCy;
            playerJoystickActive = false;
            invalidate();
            return;
        }
        if (toggleButton.contains(x, y)) {
            autoMoveMode = !autoMoveMode;
            prefs.edit().putBoolean("自动移动模式", autoMoveMode).apply();
            joystickPointer = -1;
            joystickKnobX = joystickCx;
            joystickKnobY = joystickCy;
            playerJoystickActive = false;
            if (!autoMoveMode && player != null) player.wanted = Dir.NONE;
            showMessage(autoMoveMode ? "已切换为自动移动：选定方向后持续前进" : "已切换为手动操控：松手或回中即停", 1.8f);
            invalidate();
            return;
        }
        if (bombButton.contains(x, y)) {
            dropNormalBomb();
            invalidate();
            return;
        }
        if (companionOnly || player == null || !player.alive || y < controlTop) return;
        if (joystickPointer < 0 && distance(x, y, joystickCx, joystickCy) <= joystickRadius * 1.55f) {
            joystickPointer = pointerId;
            updateJoystickInput(x, y);
        }
    }

    private void updateJoystickInput(float x, float y) {
        if (screen != Screen.PLAYING || systemPaused || joystickRadius <= 0f) return;
        float dx = x - joystickCx, dy = y - joystickCy;
        float len = (float) Math.hypot(dx, dy);
        float scale = len > joystickRadius ? joystickRadius / len : 1f;
        joystickKnobX = joystickCx + dx * scale;
        joystickKnobY = joystickCy + dy * scale;
        float threshold = joystickRadius * (playerJoystickActive ? 0.085f : 0.12f);
        playerJoystickActive = len > threshold;
        if (playerJoystickActive && player != null) {
            float ax = Math.abs(dx), ay = Math.abs(dy);
            boolean horizontal = ax > ay;
            // Retain an axis only close to its diagonal boundary, never an obsolete sign.
            if (joystickDirection.dx != 0 && ay < ax * 1.14f) horizontal = true;
            else if (joystickDirection.dy != 0 && ax < ay * 1.14f) horizontal = false;
            joystickDirection = horizontal ? (dx < 0 ? Dir.LEFT : Dir.RIGHT) : (dy < 0 ? Dir.UP : Dir.DOWN);
            player.wanted = joystickDirection;
        } else {
            joystickDirection = Dir.NONE;
            if (!autoMoveMode && player != null) player.wanted = Dir.NONE;
        }
        postInvalidateOnAnimation();
    }

    private void resetJoystick() {
        joystickPointer = -1;
        joystickKnobX = joystickCx; joystickKnobY = joystickCy;
        playerJoystickActive = false;
        joystickDirection = Dir.NONE;
        if (!autoMoveMode && player != null) player.wanted = Dir.NONE;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            resetJoystick();
            if (screen == Screen.PLAYING) screen = Screen.PAUSED;
            lastFrameNanos = 0L;
            invalidate();
        }
    }

    public boolean handleBack() {
        switch (screen) {
            case PLAYING:
                screen = Screen.PAUSED;
                resetJoystick();
                invalidate();
                return true;
            case PAUSED:
                screen = Screen.PLAYING;
                lastFrameNanos = 0L;
                invalidate();
                return true;
            case MAP_SELECT:
            case GUIDE:
                screen = Screen.HOME;
                invalidate();
                return true;
            case GAME_OVER:
            case WIN:
                screen = Screen.MAP_SELECT;
                invalidate();
                return true;
            default:
                return false;
        }
    }

    public void pauseFromSystem() {
        systemPaused = true;
        resetJoystick();
        setKeepScreenOn(false);
        if (screen == Screen.PLAYING) screen = Screen.PAUSED;
        lastFrameNanos = 0L;
    }

    public void resumeFromSystem() {
        systemPaused = false;
        resetJoystick();
        lastFrameNanos = 0L;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (tone != null) {
            try {
                tone.release();
            } catch (RuntimeException ignored) {
                // 音频系统已被厂商进程回收时无需再次释放。
            }
            tone = null;
        }
        if (wallCache != null) {
            wallCache = null;
            wallCacheCanvas = null;
            wallCacheDirty = true;
        }
        super.onDetachedFromWindow();
    }

    private void playTone(int toneType, int durationMs) {
        if (audioUnavailable) return;
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 35);
            tone.startTone(toneType, durationMs);
        } catch (RuntimeException | LinkageError ignored) {
            // 某些静音、受限或厂商音频实现异常的设备会拒绝创建音轨。
            audioUnavailable = true;
            tone = null;
        }
    }

    private void vibrate(long millis) {
        try {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(millis);
            }
        } catch (SecurityException ignored) {
            // 没有振动权限时静默降级。
        }
    }

    private float gx(int gridX) { return originX + (gridX + 0.5f) * cell; }
    private float gy(int gridY) { return originY + (gridY + 0.5f) * cell; }
    private float gxFloat(float gridX) { return originX + (gridX + 0.5f) * cell; }
    private float gyFloat(float gridY) { return originY + (gridY + 0.5f) * cell; }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float approach(float value, float target, float amount) {
        if (value < target) return Math.min(target, value + amount);
        return Math.max(target, value - amount);
    }

    private static float lerp(float a, float b, float p) { return a + (b - a) * p; }
    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static int mixColor(int a, int b, float p) {
        p = clamp01(p);
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a), aa = Color.alpha(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b), ba = Color.alpha(b);
        return Color.argb((int) lerp(aa, ba, p), (int) lerp(ar, br, p),
                (int) lerp(ag, bg, p), (int) lerp(ab, bb, p));
    }
}
