import java.util.*;
import java.io.*;
import java.nio.file.*;

/* ============================================================
 *  Cambodia Smart Travel Planner — v2.0
 *  Author : Group Project
 *  Updated: 2026
 *
 *  Architecture:
 *    JsonParser       — lightweight JSON reader (no libs needed)
 *    Destination      — model loaded from cambodia_destinations.json
 *    TravelRequest    — user preferences
 *    RecommendationEngine — weighted scoring + ranking
 *    TravelPlanner    — main application with full error handling
 *
 *  Error Handling:
 *    - FileNotFoundException   : JSON file missing
 *    - IOException             : file unreadable
 *    - JsonParseException      : malformed JSON
 *    - IllegalArgumentException: bad field values (negative cost, etc.)
 *    - NumberFormatException   : corrupted numeric fields
 *    - InputMismatchException  : invalid user input
 *    - NoSuchElementException  : empty database
 *    - RuntimeException        : any unexpected crash
 * ============================================================ */

// ─────────────────────────────────────────────────────────────
//  CUSTOM EXCEPTION: JsonParseException
//  Thrown when the JSON file is structurally invalid
// ─────────────────────────────────────────────────────────────
class JsonParseException extends Exception {
    public JsonParseException(String message) {
        super("[JsonParseException] " + message);
    }
    public JsonParseException(String message, Throwable cause) {
        super("[JsonParseException] " + message, cause);
    }
}


// ─────────────────────────────────────────────────────────────
//  CUSTOM EXCEPTION: DataValidationException
//  Thrown when a destination record has invalid field values
// ─────────────────────────────────────────────────────────────
class DataValidationException extends Exception {
    public DataValidationException(String message) {
        super("[DataValidationException] " + message);
    }
}


// ─────────────────────────────────────────────────────────────
//  CLASS: JsonParser
//  Lightweight manual JSON parser — no external libraries.
//  Parses the exact structure of cambodia_destinations.json.
//
//  Handles:
//    - Malformed JSON structure  → JsonParseException
//    - Missing required fields   → JsonParseException
//    - Wrong numeric types       → NumberFormatException (caught)
//    - Empty file                → JsonParseException
// ─────────────────────────────────────────────────────────────
class JsonParser {

    // ── Extract the value of a string field: "key":"value" ────
    public static String extractString(String block, String key)
            throws JsonParseException {
        // Pattern: "key":"value"  or  "key": "value"
        String searchKey = "\"" + key + "\"";
        int keyIdx = block.indexOf(searchKey);
        if (keyIdx == -1)
            throw new JsonParseException(
                "Missing required field: \"" + key + "\"");

        int colonIdx = block.indexOf(":", keyIdx + searchKey.length());
        if (colonIdx == -1)
            throw new JsonParseException(
                "Malformed JSON near field: \"" + key + "\"");

        // Find opening quote
        int openQuote = block.indexOf("\"", colonIdx + 1);
        if (openQuote == -1)
            throw new JsonParseException(
                "Expected string value for field: \"" + key + "\"");

        // Find closing quote (handle escaped quotes)
        int closeQuote = openQuote + 1;
        while (closeQuote < block.length()) {
            if (block.charAt(closeQuote) == '"' &&
                block.charAt(closeQuote - 1) != '\\') break;
            closeQuote++;
        }
        if (closeQuote >= block.length())
            throw new JsonParseException(
                "Unterminated string for field: \"" + key + "\"");

        return block.substring(openQuote + 1, closeQuote);
    }

    // ── Extract the value of a numeric field: "key":number ────
    public static double extractNumber(String block, String key)
            throws JsonParseException {
        String searchKey = "\"" + key + "\"";
        int keyIdx = block.indexOf(searchKey);
        if (keyIdx == -1)
            throw new JsonParseException(
                "Missing required numeric field: \"" + key + "\"");

        int colonIdx = block.indexOf(":", keyIdx + searchKey.length());
        if (colonIdx == -1)
            throw new JsonParseException(
                "Malformed JSON near numeric field: \"" + key + "\"");

        // Collect digits (including minus sign and decimal point)
        StringBuilder num = new StringBuilder();
        int i = colonIdx + 1;
        while (i < block.length() && " \t\n\r".indexOf(block.charAt(i)) >= 0) i++;
        if (i < block.length() && block.charAt(i) == '-') {
            num.append('-'); i++;
        }
        while (i < block.length() &&
               (Character.isDigit(block.charAt(i)) || block.charAt(i) == '.')) {
            num.append(block.charAt(i)); i++;
        }
        if (num.length() == 0 || num.toString().equals("-"))
            throw new JsonParseException(
                "Expected numeric value for field: \"" + key + "\"");

        try {
            return Double.parseDouble(num.toString());
        } catch (NumberFormatException e) {
            throw new JsonParseException(
                "Cannot parse number for field \"" + key +
                "\": " + num, e);
        }
    }

    // ── Extract a JSON array of strings: "key":["a","b",...] ──
    public static List<String> extractStringArray(String block, String key)
            throws JsonParseException {
        String searchKey = "\"" + key + "\"";
        int keyIdx = block.indexOf(searchKey);
        if (keyIdx == -1)
            throw new JsonParseException(
                "Missing required array field: \"" + key + "\"");

        int bracketOpen = block.indexOf("[", keyIdx);
        if (bracketOpen == -1)
            throw new JsonParseException(
                "Expected array '[' for field: \"" + key + "\"");

        int bracketClose = block.indexOf("]", bracketOpen);
        if (bracketClose == -1)
            throw new JsonParseException(
                "Unclosed array ']' for field: \"" + key + "\"");

        String arrayContent = block.substring(bracketOpen + 1, bracketClose);
        List<String> result = new ArrayList<>();

        // Parse each quoted string inside the array
        int pos = 0;
        while (pos < arrayContent.length()) {
            int open = arrayContent.indexOf("\"", pos);
            if (open == -1) break;
            int close = open + 1;
            while (close < arrayContent.length()) {
                if (arrayContent.charAt(close) == '"' &&
                    arrayContent.charAt(close - 1) != '\\') break;
                close++;
            }
            if (close >= arrayContent.length()) break;
            result.add(arrayContent.substring(open + 1, close));
            pos = close + 1;
        }
        return result;
    }

    // ── Split the top-level destinations array into blocks ────
    // Each block is one { ... } object
    public static List<String> splitObjects(String json)
            throws JsonParseException {
        // Find the outer array
        int arrayStart = json.indexOf("[");
        int arrayEnd   = json.lastIndexOf("]");
        if (arrayStart == -1 || arrayEnd == -1 || arrayStart >= arrayEnd)
            throw new JsonParseException(
                "JSON must contain a top-level array [...] of destination objects.");

        String arrayBody = json.substring(arrayStart + 1, arrayEnd);
        List<String> objects = new ArrayList<>();

        int depth = 0;
        int start = -1;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    objects.add(arrayBody.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        if (objects.isEmpty())
            throw new JsonParseException(
                "No destination objects found in JSON array.");
        return objects;
    }
}


// ─────────────────────────────────────────────────────────────
//  MODEL: Destination
//  Loaded from cambodia_destinations.json via JsonParser.
//
//  Scoring weights:
//    mood match  → +3
//    budget fit  → +2
//    season match→ +1
//    bonus rating→ +0.5 if rating >= 4.5
// ─────────────────────────────────────────────────────────────
class Destination {

    private final String       name;
    private final String       province;
    private final int          cost;           // daily cost per person ($)
    private final String       season;         // dry / wet / both
    private final String       mood;           // heritage / nature / beach / etc.
    private final double       latitude;
    private final double       longitude;
    private final List<String> activities;
    private final double       rating;

    // ── Constructor with full validation ──────────────────────
    public Destination(String name, String province, int cost,
                       String season, String mood,
                       double latitude, double longitude,
                       List<String> activities, double rating)
            throws DataValidationException {

        // Logical error: validate every field
        if (name == null || name.isBlank())
            throw new DataValidationException("Destination name cannot be empty.");
        if (province == null || province.isBlank())
            throw new DataValidationException("Province cannot be empty for: " + name);
        if (cost < 0)
            throw new DataValidationException(
                "Cost cannot be negative for destination: " + name +
                " (got: " + cost + ")");
        if (cost > 10000)
            throw new DataValidationException(
                "Suspiciously high cost for: " + name +
                " (got: $" + cost + ")");
        if (season == null || season.isBlank())
            throw new DataValidationException("Season cannot be empty for: " + name);
        if (!season.matches("dry|wet|both|cool|harvest"))
            throw new DataValidationException(
                "Unknown season value '" + season + "' for: " + name +
                ". Expected: dry, wet, both, cool, or harvest.");
        if (mood == null || mood.isBlank())
            throw new DataValidationException("Mood cannot be empty for: " + name);
        if (latitude < -90 || latitude > 90)
            throw new DataValidationException(
                "Invalid latitude " + latitude + " for: " + name);
        if (longitude < -180 || longitude > 180)
            throw new DataValidationException(
                "Invalid longitude " + longitude + " for: " + name);
        if (activities == null || activities.isEmpty())
            throw new DataValidationException(
                "Activities list cannot be empty for: " + name);
        if (rating < 0.0 || rating > 5.0)
            throw new DataValidationException(
                "Rating " + rating + " out of range [0-5] for: " + name);

        this.name       = name.trim();
        this.province   = province.trim();
        this.cost       = cost;
        this.season     = season.trim().toLowerCase();
        this.mood       = mood.trim().toLowerCase();
        this.latitude   = latitude;
        this.longitude  = longitude;
        this.activities = Collections.unmodifiableList(activities);
        this.rating     = rating;
    }

    // ── Getters ───────────────────────────────────────────────
    public String       getName()       { return name; }
    public String       getProvince()   { return province; }
    public int          getCost()       { return cost; }
    public String       getSeason()     { return season; }
    public String       getMood()       { return mood; }
    public List<String> getActivities() { return activities; }
    public double       getRating()     { return rating; }

    // ── Weighted scoring ──────────────────────────────────────
    /*
     * Score breakdown:
     *   +3  if mood exactly matches (or is "both")
     *   +2  if daily budget covers the destination cost
     *   +1  if season matches (or destination season is "both")
     *   +0.5 bonus if rating >= 4.5
     */
    public double calculateScore(TravelRequest request) {
        double score = 0;

        // Mood matching (case-insensitive)
        String reqMood = request.getMood().toLowerCase();
        if (this.mood.equals(reqMood) ||
            reqMood.contains(this.mood) ||
            this.mood.contains(reqMood))
            score += 3;

        // Budget: user's daily budget must cover the destination cost
        if (request.getBudgetPerDay() >= this.cost)
            score += 2;

        // Season matching
        String reqSeason = request.getSeason().toLowerCase();
        if (this.season.equals("both") ||
            this.season.equals(reqSeason) ||
            reqSeason.equals("both"))
            score += 1;

        // Rating bonus
        if (this.rating >= 4.5) score += 0.5;

        return score;
    }

    // ── Display formatted result ──────────────────────────────
    public void displayPlan(TravelRequest request, double score) {
        int totalCost = this.cost * request.getDuration() * request.getMembers();

        System.out.println("\n================================================");
        System.out.println(" Destination   : " + name);
        System.out.println(" Province      : " + province);
        System.out.printf(" Match Score   : %.1f / 6.5%n", score);
        System.out.println(" Daily Cost    : $" + cost + " per person");
        System.out.println(" Total Cost    : $" + totalCost +
                           " (" + request.getDuration() + " days × " +
                           request.getMembers() + " people)");
        System.out.printf(" Rating        : %.1f / 5.0 ⭐%n", rating);
        System.out.println(" Best Season   : " + season);
        System.out.println(" Activities    : " + String.join(", ", activities));
        System.out.println("================================================");
    }
}


// ─────────────────────────────────────────────────────────────
//  MODEL: TravelRequest
// ─────────────────────────────────────────────────────────────
class TravelRequest {

    private final String mood;
    private final int    budgetPerDay;
    private final int    duration;
    private final String season;
    private final int    members;

    public TravelRequest(String mood, int budgetPerDay,
                         int duration, String season, int members)
            throws IllegalArgumentException {

        // Logical validation
        if (mood == null || mood.isBlank())
            throw new IllegalArgumentException("Mood cannot be empty.");
        if (budgetPerDay <= 0)
            throw new IllegalArgumentException(
                "Budget per day must be positive. Got: " + budgetPerDay);
        if (duration <= 0)
            throw new IllegalArgumentException(
                "Duration must be at least 1 day. Got: " + duration);
        if (duration > 365)
            throw new IllegalArgumentException(
                "Duration too long (max 365 days). Got: " + duration);
        if (season == null || season.isBlank())
            throw new IllegalArgumentException("Season cannot be empty.");
        if (members <= 0)
            throw new IllegalArgumentException(
                "Group size must be at least 1. Got: " + members);
        if (members > 100)
            throw new IllegalArgumentException(
                "Group size too large (max 100). Got: " + members);

        this.mood         = mood.trim().toLowerCase();
        this.budgetPerDay = budgetPerDay;
        this.duration     = duration;
        this.season       = season.trim().toLowerCase();
        this.members      = members;
    }

    public String getMood()          { return mood; }
    public int    getBudgetPerDay()  { return budgetPerDay; }
    public int    getDuration()      { return duration; }
    public String getSeason()        { return season; }
    public int    getMembers()       { return members; }
}


// ─────────────────────────────────────────────────────────────
//  SERVICE: RecommendationEngine
//  Scores all destinations and returns sorted top results.
//
//  Handles:
//    - Empty database         → NoSuchElementException
//    - All scores = 0         → warning printed, returns lowest-cost
// ─────────────────────────────────────────────────────────────
class RecommendationEngine {

    public static List<Map.Entry<Destination, Double>> recommend(
            List<Destination> database,
            TravelRequest request,
            int topN) {

        // Runtime error: empty database
        if (database == null || database.isEmpty())
            throw new NoSuchElementException(
                "[Engine] Cannot recommend — destination database is empty.");

        if (topN <= 0)
            throw new IllegalArgumentException(
                "[Engine] topN must be >= 1. Got: " + topN);

        Map<Destination, Double> scoreMap = new LinkedHashMap<>();
        for (Destination d : database) {
            double score = d.calculateScore(request);
            scoreMap.put(d, score);
        }

        // Sort descending by score, then by rating as tiebreaker
        List<Map.Entry<Destination, Double>> sorted =
            new ArrayList<>(scoreMap.entrySet());

        sorted.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            // Tiebreaker: higher rating wins
            return Double.compare(
                b.getKey().getRating(),
                a.getKey().getRating());
        });

        // Warn if no good matches
        if (!sorted.isEmpty() && sorted.get(0).getValue() == 0) {
            System.out.println(
                "\n  ⚠ Warning: No destinations closely matched your preferences.");
            System.out.println(
                "  Showing results by rating instead.\n");
        }

        return sorted.subList(0, Math.min(topN, sorted.size()));
    }
}


// ─────────────────────────────────────────────────────────────
//  SERVICE: DatabaseLoader
//  Reads cambodia_destinations.json and builds Destination list.
//
//  Handles:
//    - File not found         → FileNotFoundException
//    - Cannot read file       → IOException
//    - Malformed JSON         → JsonParseException
//    - Invalid field values   → DataValidationException (skips record)
//    - Empty result           → RuntimeException
// ─────────────────────────────────────────────────────────────
class DatabaseLoader {

    public static List<Destination> load(String jsonFilePath)
            throws FileNotFoundException, IOException, JsonParseException {

        // 1. Check file exists
        File file = new File(jsonFilePath);
        if (!file.exists())
            throw new FileNotFoundException(
                "JSON database not found: " + jsonFilePath +
                "\nMake sure cambodia_destinations.json is in the same folder.");
        if (!file.canRead())
            throw new IOException(
                "Cannot read file (permission denied): " + jsonFilePath);
        if (file.length() == 0)
            throw new JsonParseException(
                "JSON file is empty: " + jsonFilePath);

        // 2. Read entire file
        String json;
        try {
            json = new String(Files.readAllBytes(Paths.get(jsonFilePath)));
        } catch (IOException e) {
            throw new IOException(
                "Failed to read JSON file: " + jsonFilePath, e);
        }

        if (json.isBlank())
            throw new JsonParseException("JSON file contains only whitespace.");

        // 3. Parse objects
        List<String> objects = JsonParser.splitObjects(json);

        List<Destination> destinations = new ArrayList<>();
        int skipped = 0;

        for (int i = 0; i < objects.size(); i++) {
            String obj = objects.get(i);
            try {
                String       name       = JsonParser.extractString(obj, "name");
                String       province   = JsonParser.extractString(obj, "province");
                int          cost       = (int) JsonParser.extractNumber(obj, "cost");
                String       season     = JsonParser.extractString(obj, "season");
                String       mood       = JsonParser.extractString(obj, "mood");
                double       latitude   = JsonParser.extractNumber(obj, "latitude");
                double       longitude  = JsonParser.extractNumber(obj, "longitude");
                List<String> activities = JsonParser.extractStringArray(obj, "activities");
                double       rating     = JsonParser.extractNumber(obj, "rating");

                destinations.add(new Destination(
                    name, province, cost, season, mood,
                    latitude, longitude, activities, rating));

            } catch (JsonParseException e) {
                System.err.println(
                    "  [WARN] Skipping record #" + (i+1) +
                    " — JSON parse error: " + e.getMessage());
                skipped++;
            } catch (DataValidationException e) {
                System.err.println(
                    "  [WARN] Skipping record #" + (i+1) +
                    " — Validation error: " + e.getMessage());
                skipped++;
            }
        }

        if (skipped > 0)
            System.out.println(
                "  [INFO] Loaded " + destinations.size() +
                " destinations (" + skipped + " records skipped due to errors).");
        else
            System.out.println(
                "  [INFO] Successfully loaded " + destinations.size() +
                " destinations from database.");

        // 4. Runtime check: at least some data loaded
        if (destinations.isEmpty())
            throw new RuntimeException(
                "No valid destinations could be loaded from: " + jsonFilePath);

        return destinations;
    }
}


// ─────────────────────────────────────────────────────────────
//  MAIN APPLICATION: TravelPlanner
// ─────────────────────────────────────────────────────────────
public class TravelPlanner {

    // Valid mood options mapped from user-friendly to JSON values
    private static final Map<String, String> MOOD_MAP = new LinkedHashMap<>();
    static {
        MOOD_MAP.put("1", "heritage");
        MOOD_MAP.put("2", "nature");
        MOOD_MAP.put("3", "beach");
        MOOD_MAP.put("4", "adventure");
        MOOD_MAP.put("5", "city");
    }

    private static final Map<String, String> SEASON_MAP = new LinkedHashMap<>();
    static {
        SEASON_MAP.put("1", "dry");
        SEASON_MAP.put("2", "wet");
        SEASON_MAP.put("3", "both");
    }

    // ── main ──────────────────────────────────────────────────
    public static void main(String[] args) {

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║   CAMBODIA SMART TRAVEL PLANNER  v2.0       ║");
        System.out.println("║   Powered by cambodia_destinations.json      ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        // ── Load database ─────────────────────────────────────
        List<Destination> database = null;
        String jsonPath = "cambodia_destinations.json";

        try {
            System.out.println("\n  Loading destination database...");
            database = DatabaseLoader.load(jsonPath);
        } catch (FileNotFoundException e) {
            System.err.println("\n  [FATAL] " + e.getMessage());
            System.err.println("  Please ensure the JSON file is in the same directory.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("\n  [FATAL] I/O error: " + e.getMessage());
            System.exit(1);
        } catch (JsonParseException e) {
            System.err.println("\n  [FATAL] " + e.getMessage());
            System.err.println("  The JSON file may be corrupted. Please check its format.");
            System.exit(1);
        } catch (RuntimeException e) {
            System.err.println("\n  [FATAL] " + e.getMessage());
            System.exit(1);
        }

        // ── Main application loop ─────────────────────────────
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            try {
                TravelRequest request = collectUserInput(scanner);

                List<Map.Entry<Destination, Double>> results =
                    RecommendationEngine.recommend(database, request, 5);

                displayResults(results, request);

            } catch (IllegalArgumentException e) {
                // Logical error from TravelRequest validation
                System.err.println("\n  [Input Error] " + e.getMessage());
                System.out.println("  Please try again.\n");
                continue;
            } catch (NoSuchElementException e) {
                // Database became empty mid-session (shouldn't happen normally)
                System.err.println("\n  [Engine Error] " + e.getMessage());
                System.exit(1);
            } catch (Exception e) {
                // Catch-all for any unexpected runtime error
                System.err.println("\n  [Unexpected Error] " + e.getMessage());
                System.out.println("  Returning to menu...\n");
                continue;
            }

            // Ask to continue
            System.out.print("\n  Plan another trip? (yes / no): ");
            try {
                String answer = scanner.nextLine().trim().toLowerCase();
                running = answer.equals("yes") || answer.equals("y");
            } catch (NoSuchElementException e) {
                // Input stream closed
                running = false;
            }
        }

        System.out.println("\n  Thank you for using Cambodia Smart Travel Planner!");
        System.out.println("  Have a wonderful journey! 🇰🇭\n");
        scanner.close();
    }

    // ── collectUserInput ──────────────────────────────────────
    private static TravelRequest collectUserInput(Scanner scanner)
            throws IllegalArgumentException {

        System.out.println("\n══════════════════════════════════════════════");
        System.out.println("  PLAN YOUR TRIP");
        System.out.println("══════════════════════════════════════════════");

        // Mood selection
        System.out.println("\n  What kind of trip are you looking for?");
        System.out.println("    1. Heritage & History");
        System.out.println("    2. Nature & Wildlife");
        System.out.println("    3. Beach & Island");
        System.out.println("    4. Adventure & Trekking");
        System.out.println("    5. City & Nightlife");
        String moodChoice = readChoice(scanner, "  Your choice (1-5): ", MOOD_MAP.keySet());
        String mood = MOOD_MAP.get(moodChoice);

        // Budget
        int budget = readPositiveInt(scanner,
            "\n  Daily budget per person (USD, e.g. 30, 80, 150): $");

        // Duration
        int duration = readPositiveInt(scanner,
            "  Trip duration (days, e.g. 3, 7, 14): ");

        // Season
        System.out.println("\n  Preferred travel season:");
        System.out.println("    1. Dry Season (Nov – Apr)");
        System.out.println("    2. Wet Season  (May – Oct)");
        System.out.println("    3. Any / Both seasons");
        String seasonChoice = readChoice(scanner, "  Your choice (1-3): ", SEASON_MAP.keySet());
        String season = SEASON_MAP.get(seasonChoice);

        // Group size
        int members = readPositiveInt(scanner,
            "  Group size (number of people): ");

        return new TravelRequest(mood, budget, duration, season, members);
    }

    // ── displayResults ────────────────────────────────────────
    private static void displayResults(
            List<Map.Entry<Destination, Double>> results,
            TravelRequest request) {

        System.out.println("\n══════════════════════════════════════════════");
        System.out.println("  TOP " + results.size() + " RECOMMENDED DESTINATIONS");
        System.out.println("══════════════════════════════════════════════");
        System.out.println("  Preferences: " + request.getMood() +
                           " | $" + request.getBudgetPerDay() + "/day" +
                           " | " + request.getDuration() + " days" +
                           " | " + request.getMembers() + " people" +
                           " | " + request.getSeason() + " season");

        for (int i = 0; i < results.size(); i++) {
            Map.Entry<Destination, Double> entry = results.get(i);
            System.out.println("\n  #" + (i + 1) + " Recommendation");
            entry.getKey().displayPlan(request, entry.getValue());
        }
    }

    // ── readPositiveInt ───────────────────────────────────────
    private static int readPositiveInt(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    System.out.println("  ✗ Input cannot be empty. Please enter a number.");
                    continue;
                }
                int value = Integer.parseInt(line);
                if (value <= 0) {
                    System.out.println("  ✗ Value must be greater than 0. Got: " + value);
                    continue;
                }
                return value;
            } catch (NumberFormatException e) {
                System.out.println("  ✗ Invalid input. Please enter a whole number (e.g. 50).");
            } catch (NoSuchElementException e) {
                throw new RuntimeException("Input stream closed unexpectedly.");
            }
        }
    }

    // ── readChoice ────────────────────────────────────────────
    private static String readChoice(Scanner scanner, String prompt,
                                     Set<String> validChoices) {
        while (true) {
            System.out.print(prompt);
            try {
                String line = scanner.nextLine().trim();
                if (validChoices.contains(line)) return line;
                System.out.println(
                    "  ✗ Invalid choice '" + line + "'. " +
                    "Please enter one of: " + validChoices);
            } catch (NoSuchElementException e) {
                throw new RuntimeException("Input stream closed unexpectedly.");
            }
        }
    }
}
