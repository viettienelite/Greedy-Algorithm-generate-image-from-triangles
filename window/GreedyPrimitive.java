import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

public class GreedyPrimitive {
    // ==========================================
    // CẤU HÌNH (SETTINGS)
    // ==========================================
    static final String INPUT_FILENAME = "input.jpg";
    
    // 1. Số lượng hình tối đa (Steps)
    static final int MAX_SHAPES = 500; 
    
    // 2. Độ phân giải tính toán (Computation Size)
    static final int TARGET_WIDTH = 800; 
    
    // 3. Số lượng hình ngẫu nhiên mỗi bước Search
    static final int SEARCH_CANDIDATES = 1000; 
    
    // 4. Dừng tối ưu hình hiện tại sau bao nhiêu lần thất bại
    static final int MAX_MUTATION_FAILURES = 100; 
    
    // 5. CẤU HÌNH ALPHA (ĐỘ TRONG SUỐT)
    static final int START_ALPHA = 30; // 0-255 (128 ~ 50%)
    
    // --- [MỚI] BẬT/TẮT AUTO ALPHA ---
    // true: Alpha sẽ thay đổi ngẫu nhiên để tìm màu tốt nhất
    // false: Alpha cố định ở START_ALPHA, chỉ thay đổi vị trí
    static final boolean ENABLE_AUTO_ALPHA = true; 

    // --- DATA CLASSES ---
    static class Point { float x, y; Point(float x, float y) { this.x = x; this.y = y; } }
    
    static class Triangle {
        Point p1, p2, p3;
        int r, g, b, a;
        Triangle(Point p1, Point p2, Point p3, int r, int g, int b, int a) {
            this.p1 = p1; this.p2 = p2; this.p3 = p3;
            this.r = r; this.g = g; this.b = b; this.a = a;
        }
        Triangle deepCopy() { return new Triangle(new Point(p1.x, p1.y), new Point(p2.x, p2.y), new Point(p3.x, p3.y), r, g, b, a); }
    }

    static class ScanResult {
        int r, g, b;
        long score; 
    }

    static Random random = new Random();
    static int workW, workH;
    static int[] targetPixels;

    public static void main(String[] args) {
        try {
            // 1. SETUP
            File outputDir = new File(".");
            new File(outputDir, "json").mkdirs();
            new File(outputDir, "svg").mkdirs();

            System.out.println("=== PRIMITIVE.JS JAVA PORT (REALTIME SAVE) ===");
            System.out.println("Auto Alpha: " + (ENABLE_AUTO_ALPHA ? "ON" : "OFF"));

            // 2. LOAD ẢNH
            File inputFile = new File(INPUT_FILENAME);
            if (!inputFile.exists()) { System.out.println("Missing input.jpg"); return; }
            BufferedImage originalImage = ImageIO.read(inputFile);
            
            // Resize về 800px (Computation Size)
            double ratio = (double) TARGET_WIDTH / originalImage.getWidth();
            workW = TARGET_WIDTH;
            workH = (int) (originalImage.getHeight() * ratio);

            BufferedImage targetImage = new BufferedImage(workW, workH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = targetImage.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(originalImage, 0, 0, workW, workH, null);
            g.dispose();
            targetPixels = ((DataBufferInt) targetImage.getRaster().getDataBuffer()).getData();

            // 3. AUTO DETECT BACKGROUND COLOR
            BufferedImage currentCanvas = new BufferedImage(workW, workH, BufferedImage.TYPE_INT_ARGB);
            int[] avgBg = getAverageColor(targetPixels);
            fillColor(currentCanvas, avgBg[0], avgBg[1], avgBg[2]);
            
            List<Triangle> committedShapes = new ArrayList<>();
            // Tính lỗi tổng thể (Squared Error)
            long currentTotalError = calculateFullErrorL2(getPixels(currentCanvas), targetPixels);
            long maxTotalError = calculateFullErrorL2(new int[workW*workH], targetPixels); 

            System.out.println("Canvas initialized with Auto-Color: RGB(" + avgBg[0] + "," + avgBg[1] + "," + avgBg[2] + ")");

            // Thread Pool
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            ForkJoinPool pool = new ForkJoinPool(threads);

            // 4. MAIN LOOP 
            for (int i = 0; i < MAX_SHAPES; i++) {
                
                final BufferedImage baseCanvas = currentCanvas; 

                // --- GIAI ĐOẠN 1: SEARCH ---
                Triangle bestT = pool.submit(() -> 
                    IntStream.range(0, SEARCH_CANDIDATES).parallel()
                    .mapToObj(k -> {
                        Triangle t = randomTriangle();
                        ScanResult res = computeColorAndScoreL2(t, baseCanvas, targetPixels);
                        if (res.score > 0) { 
                            t.r = res.r; t.g = res.g; t.b = res.b;
                            return new Object[]{t, res.score};
                        }
                        return null;
                    })
                    .filter(obj -> obj != null)
                    .max((o1, o2) -> Long.compare((long)o1[1], (long)o2[1]))
                    .map(obj -> (Triangle)obj[0])
                    .orElse(null)
                ).get();

                if (bestT == null) continue;

                // --- GIAI ĐOẠN 2: OPTIMIZATION ---
                long bestScore = computeColorAndScoreL2(bestT, baseCanvas, targetPixels).score;
                int failedAttempts = 0;
                int totalAttempts = 0;

                while (failedAttempts < MAX_MUTATION_FAILURES) {
                    Triangle mutated = bestT.deepCopy();
                    mutate(mutated); 
                    
                    ScanResult res = computeColorAndScoreL2(mutated, baseCanvas, targetPixels);
                    
                    if (res.score > bestScore) {
                        bestScore = res.score;
                        mutated.r = res.r; mutated.g = res.g; mutated.b = res.b;
                        bestT = mutated;
                        failedAttempts = 0; 
                    } else {
                        failedAttempts++;
                    }
                    totalAttempts++;
                }

                // --- GIAI ĐOẠN 3: COMMIT ---
                committedShapes.add(bestT);
                drawTriangle(currentCanvas, bestT); 
                
                currentTotalError -= bestScore;
                if (currentTotalError < 0) currentTotalError = 0;
                
                double fit = 100.0 * (1.0 - (double)currentTotalError / (double)maxTotalError);
                System.out.print("\rShape: " + (i + 1) + " | Score: " + String.format(Locale.US, "%.4f", fit) + "% | Opts: " + totalAttempts);
                
                // SAVE FILE NGAY LẬP TỨC
                saveArtifacts(i + 1, committedShapes, fit, avgBg);
            }
            System.out.println("\nDone!");
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ==========================================================
    // CORE LOGIC (SCANLINE + MATH)
    // ==========================================================

    static ScanResult computeColorAndScoreL2(Triangle t, BufferedImage current, int[] target) {
        int[] bounds = getBounds(t);
        int minX = bounds[0], minY = bounds[1], maxX = bounds[2], maxY = bounds[3];
        int w = maxX - minX;
        int h = maxY - minY;
        
        if (w <= 0 || h <= 0) return new ScanResult();

        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mask.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(-minX, -minY);
        g.setColor(Color.WHITE);
        GeneralPath path = new GeneralPath();
        path.moveTo(t.p1.x, t.p1.y); path.lineTo(t.p2.x, t.p2.y); path.lineTo(t.p3.x, t.p3.y); path.closePath();
        g.fill(path);
        g.dispose();

        int[] maskData = ((DataBufferInt) mask.getRaster().getDataBuffer()).getData();
        int[] currentData = ((DataBufferInt) current.getRaster().getDataBuffer()).getData();

        long rSum = 0, gSum = 0, bSum = 0;
        double aSum = 0;
        
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int shapeAlpha = (maskData[y * w + x] >> 24) & 0xFF;
                if (shapeAlpha == 0) continue;

                int idx = (minY + y) * workW + (minX + x);
                double a = (shapeAlpha / 255.0) * (t.a / 255.0);
                
                int tr = (targetPixels[idx] >> 16) & 0xFF;
                int tg = (targetPixels[idx] >> 8) & 0xFF;
                int tb = targetPixels[idx] & 0xFF;
                
                int cr = (currentData[idx] >> 16) & 0xFF;
                int cg = (currentData[idx] >> 8) & 0xFF;
                int cb = currentData[idx] & 0xFF;

                rSum += (tr - cr) * a + cr * a * a;
                gSum += (tg - cg) * a + cg * a * a;
                bSum += (tb - cb) * a + cb * a * a;
                aSum += a * a;
            }
        }

        if (aSum == 0) return new ScanResult();

        int bestR = clamp((int) (rSum / aSum));
        int bestG = clamp((int) (gSum / aSum));
        int bestB = clamp((int) (bSum / aSum));

        long score = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int shapeAlpha = (maskData[y * w + x] >> 24) & 0xFF;
                if (shapeAlpha == 0) continue;

                int idx = (minY + y) * workW + (minX + x);
                double a = (shapeAlpha / 255.0) * (t.a / 255.0);
                
                int tr = (targetPixels[idx] >> 16) & 0xFF;
                int tg = (targetPixels[idx] >> 8) & 0xFF;
                int tb = targetPixels[idx] & 0xFF;
                
                int cr = (currentData[idx] >> 16) & 0xFF;
                int cg = (currentData[idx] >> 8) & 0xFF;
                int cb = currentData[idx] & 0xFF;

                int nr = (int) (cr + (bestR - cr) * a);
                int ng = (int) (cg + (bestG - cg) * a);
                int nb = (int) (cb + (bestB - cb) * a);

                long diffOld = (long)(tr-cr)*(tr-cr) + (long)(tg-cg)*(tg-cg) + (long)(tb-cb)*(tb-cb);
                long diffNew = (long)(tr-nr)*(tr-nr) + (long)(tg-ng)*(tg-ng) + (long)(tb-nb)*(tb-nb);

                score += (diffOld - diffNew);
            }
        }

        ScanResult res = new ScanResult();
        res.r = bestR; res.g = bestG; res.b = bestB;
        res.score = score;
        return res;
    }

    // ==========================================================
    // MUTATION LOGIC
    // ==========================================================

    static void mutate(Triangle t) {
        // [QUAN TRỌNG] Kiểm tra biến ENABLE_AUTO_ALPHA
        if (ENABLE_AUTO_ALPHA && random.nextInt(10) == 0) { 
            // 10% cơ hội đổi Alpha nếu được phép
            int delta = random.nextInt(21) - 10; 
            t.a = clamp(t.a + delta);
            t.a = Math.max(10, t.a); 
        } else {
            // Thay đổi vị trí
            Point p = new Point[]{t.p1, t.p2, t.p3}[random.nextInt(3)];
            float range = 20; 
            p.x = Math.max(0, Math.min(workW, p.x + (random.nextFloat() - 0.5f) * range));
            p.y = Math.max(0, Math.min(workH, p.y + (random.nextFloat() - 0.5f) * range));
        }
    }

    static Triangle randomTriangle() {
        float x = random.nextFloat() * workW;
        float y = random.nextFloat() * workH;
        float s = 30; 
        return new Triangle(
            new Point(x + (random.nextFloat() - 0.5f) * s, y + (random.nextFloat() - 0.5f) * s),
            new Point(x + (random.nextFloat() - 0.5f) * s, y + (random.nextFloat() - 0.5f) * s),
            new Point(x + (random.nextFloat() - 0.5f) * s, y + (random.nextFloat() - 0.5f) * s),
            0, 0, 0, START_ALPHA); 
    }

    // ==========================================================
    // UTILS
    // ==========================================================

    static int[] getAverageColor(int[] pixels) {
        long r = 0, g = 0, b = 0;
        for (int p : pixels) {
            r += (p >> 16) & 0xFF;
            g += (p >> 8) & 0xFF;
            b += p & 0xFF;
        }
        int c = pixels.length;
        return new int[] { (int)(r/c), (int)(g/c), (int)(b/c) };
    }

    static void fillColor(BufferedImage img, int r, int g, int b) {
        Graphics2D gr = img.createGraphics();
        gr.setColor(new Color(r, g, b));
        gr.fillRect(0, 0, workW, workH);
        gr.dispose();
    }

    static void drawTriangle(BufferedImage img, Triangle t) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        GeneralPath path = new GeneralPath();
        path.moveTo(t.p1.x, t.p1.y); path.lineTo(t.p2.x, t.p2.y); path.lineTo(t.p3.x, t.p3.y); path.closePath();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, t.a / 255.0f));
        g.setColor(new Color(t.r, t.g, t.b));
        g.fill(path);
        g.dispose();
    }
    
    static int[] getBounds(Triangle t) {
        return new int[] {
            Math.max(0, (int)Math.min(t.p1.x, Math.min(t.p2.x, t.p3.x)) - 1),
            Math.max(0, (int)Math.min(t.p1.y, Math.min(t.p2.y, t.p3.y)) - 1),
            Math.min(workW, (int)Math.max(t.p1.x, Math.max(t.p2.x, t.p3.x)) + 2),
            Math.min(workH, (int)Math.max(t.p1.y, Math.max(t.p2.y, t.p3.y)) + 2)
        };
    }

    static long calculateFullErrorL2(int[] current, int[] target) {
        long err = 0;
        for (int i = 0; i < current.length; i++) {
            int p1 = current[i]; int p2 = target[i];
            long dr = ((p1 >> 16) & 0xFF) - ((p2 >> 16) & 0xFF);
            long dg = ((p1 >> 8) & 0xFF) - ((p2 >> 8) & 0xFF);
            long db = (p1 & 0xFF) - (p2 & 0xFF);
            err += dr*dr + dg*dg + db*db;
        }
        return err;
    }

    static int clamp(int val) { return Math.max(0, Math.min(255, val)); }
    static int[] getPixels(BufferedImage img) { return ((DataBufferInt) img.getRaster().getDataBuffer()).getData(); }

    static void saveArtifacts(int count, List<Triangle> dna, double fit, int[] bg) {
        try {
            StringBuilder svg = new StringBuilder();
            svg.append(String.format(Locale.US, "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"100%%\" height=\"100%%\">", workW, workH));
            // Nền Auto Detect
            svg.append(String.format(Locale.US, "<rect width=\"100%%\" height=\"100%%\" fill=\"rgb(%d,%d,%d)\"/>", bg[0], bg[1], bg[2]));

            for (Triangle t : dna) {
                svg.append(String.format(Locale.US, "<polygon points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f\" fill=\"rgb(%d,%d,%d)\" fill-opacity=\"%.3f\" />",
                    t.p1.x, t.p1.y, t.p2.x, t.p2.y, t.p3.x, t.p3.y, t.r, t.g, t.b, t.a / 255.0));
            }
            svg.append("</svg>");

            try (FileWriter fw = new FileWriter(new File("latest.svg"))) { fw.write(svg.toString()); }
            String js = String.format(Locale.US, "window.updateDashboard({ count: %d, fit: %.4f });", count, fit);
            try (FileWriter fw = new FileWriter(new File("dashboard_data.js"))) { fw.write(js); }
        } catch (Exception e) {}
    }
}