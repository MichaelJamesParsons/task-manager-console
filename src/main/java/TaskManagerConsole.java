import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.cli.*;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.LocalTime;

public class TaskManagerConsole {
    private Gson jsonParser;
    private Options options;
    private static final String API_ENDPOINT = "http://138.197.15.79/task";
    private static volatile boolean canExecute = true;

    public static void main(String[] args) {
        final TaskManagerConsole taskManager = new TaskManagerConsole();
        taskManager.execute(args);

        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(){
            public void run(){
                canExecute = false;
                try {
                    taskManager.stopTask();
                    mainThread.join();
                } catch (InterruptedException e) {
                    System.out.println("Shutdown interrupted!");
                }
            }
        });
    }

    private TaskManagerConsole() {
        options = initializeCommandlineOptions();
        jsonParser = new Gson();
    }

    private void execute(String[] args) {
        try {
            CommandLine cli = initializeCli(args);
            Option[] parsedOptions = cli.getOptions();

            if(parsedOptions.length == 0) {
                dieWithHelper();
            } else if(parsedOptions.length > 1) {
                dieWithHelper("ERROR: Only one command may be executed at a time!");
            }

            processOption(parsedOptions[0]);

        } catch (ParseException e) {
            dieWithHelper("Invalid argument! " + e.getLocalizedMessage() + ".");
        }
    }

    private void processOption(Option option) {
        String name = option.getArgName();

        if(name.compareTo("new") == 0) {
            newTask(option.getValues());
        } else if(name.compareTo("list") == 0) {
            listTasks();
        } else if(name.compareTo("remove") == 0) {
            removeTask(Integer.parseInt(option.getValue()));
        } else if(name.compareTo("start") == 0) {
            startTask(Integer.parseInt(option.getValue()));
        } else if(name.compareTo("help") == 0) {
            dieWithHelper();
        }
    }

    /**
     * Lists all of the tasks.
     */
    private void listTasks() {
        try {
            JsonObject[] tasks = deserializeJsonArray(sendGetRequest("/all"));

            System.out.println("Your tasks (recent first):");
            for(JsonObject task : tasks) {
                System.out.println(task.get("id") + ". " + task.get("description").getAsString());
            }
        } catch (UnirestException e) {
            die("Failed to load tasks!");
        }
    }

    private void removeTask(int id) {
        try {
            JsonObject response = deserializeJsonObject(sendDeleteRequest("/" + id));
            boolean isSuccessful = response.get("success").getAsBoolean();

            if(isSuccessful) {
                System.out.println("Task " + id + " deleted!");
            } else {
                System.out.println("Failed to delete task: " + response.get("message").getAsString());
            }

        } catch (UnirestException e) {
            die("Failed to delete task. It may have already been deleted.");
        }
    }

    private void newTask(String[] args) {
        String taskName = args[0];
        boolean isStartable = false;

        if(args.length == 2) {
            isStartable = Boolean.parseBoolean(args[1]);
        }

        try {
            HttpResponse response = Unirest.post(API_ENDPOINT)
                    .header("Content-Type", "application/json")
                    .header("accept", "application/json")
                    .body("{\"description\":\"" + taskName + "\"}")
                    .asJson();

            JsonObject task = jsonParser.fromJson(response.getBody().toString(), JsonObject.class);
            System.out.println("Task added with ID " + task.get("id"));

            if(isStartable) {
                System.out.println("Starting task");
                startTask(task.get("id").getAsInt());
            }
        } catch (UnirestException e) {
            stopTask();
            System.out.println("Failed to add new task.");
        }
    }

    /**
     * @todo complete!
     * @param id
     */
    private void startTask(int id) {
        JsonObject task = getTask(id);

        if(task != null && task.get("id") != null) {
            try {
                JsonObject timer = sendPostRequest("/" + id + "/start", "");

                if(timer.get("error") != null) {
                    JsonObject runningEntry = getRunningTimeEntry(id);
                    assert runningEntry != null;
                    runTimer(task, runningEntry.get("start").getAsString());
                } else {
                    runTimer(task, timer.get("start").getAsString());
                }

            } catch (UnirestException e) {
                die("Failed to start task.");
            }
        } else {
            die("ERROR: That task doesn't exist.");
        }
    }

    private JsonObject getRunningTimeEntry(int taskId) throws UnirestException {
        JsonObject[] response = deserializeJsonArray(sendGetRequest("/timeentry/running/" + taskId));

        if(response.length > 0) {
            return response[0];
        }

        return null;
    }

    private void stopTask() {
        System.out.println("Stopping task.");
    }

    private void runTimer(JsonObject task, String startTime) {
        DateTime start = DateTime.parse(startTime);

        while(true) {
            long timeInSeconds = System.currentTimeMillis() % 1000;
            LocalTime now = new LocalTime();

            System.out.print("\r" + formatTimeDifference(start, now.toDateTimeToday()) + " (ctrl+x to stop)");

            try {
                Thread.sleep(1000 - timeInSeconds);
            } catch (InterruptedException e) {
                System.out.println("Task " + task.get("id") + " stopped!");
                break;
            }
        }
    }

    private String formatTimeDifference(DateTime start, DateTime end) {
        Duration duration = new Duration(start, end);

        if(duration.getStandardSeconds() <= 0) {
            return "Initializing timer...";
        }

        return String.format("%02d:%02d:%02d",
                duration.getStandardHours() % 24,
                duration.getStandardMinutes() % 60,
                duration.getStandardSeconds() % 60
        );
    }

    /**
     * Fetch a task by the given ID from the API.
     *
     * @param id - The ID of the task to fetch.
     * @return - The task.
     */
    private JsonObject getTask(int id) {
        try {
            return deserializeJsonObject(sendGetRequest("/" + id));
        } catch (UnirestException e) {
            die("Failed to load task.");
        }

        return null;
    }

    /**
     *
     * @param endpointSuffix - The route to send the request. (e.g /tasks/all)
     * @param payload - The json data to send with the request.
     * @return
     *
     * @throws UnirestException
     */
    private JsonObject sendPostRequest(String endpointSuffix, String payload) throws UnirestException {
        HttpResponse response = Unirest.post(API_ENDPOINT + endpointSuffix)
                .header("Content-Type", "application/json")
                .header("accept", "application/json")
                .body(payload)
                .asJson();

        return deserializeJsonObject(response.getBody().toString());
    }

    /**
     * Send a GET request to the API.
     *
     * @param endpointSuffix - The route to send the request. (e.g /tasks/all)
     * @return The API response.
     *
     * @throws UnirestException
     */
    private String sendGetRequest(String endpointSuffix) throws UnirestException {
        HttpResponse response = Unirest.get(API_ENDPOINT + endpointSuffix)
                .header("Content-Type", "application/json")
                .header("accept", "application/json")
                .asJson();

        return response.getBody().toString();
    }

    /**
     * Send a DELETE request to the API.
     *
     * @param endpointSuffix - The route to send the request. (e.g /tasks/all)
     * @return The API response.
     *
     * @throws UnirestException
     */
    private String sendDeleteRequest(String endpointSuffix) throws UnirestException {
        HttpResponse response = Unirest.delete(API_ENDPOINT + endpointSuffix)
                .header("Content-Type", "application/json")
                .header("accept", "application/json")
                .asJson();

        return response.getBody().toString();
    }

    private JsonObject deserializeJsonObject(String json) {
        return jsonParser.fromJson(json, JsonObject.class);
    }

    private JsonObject[] deserializeJsonArray(String json) {
        return jsonParser.fromJson(json, JsonObject[].class);
    }

    private CommandLine initializeCli(String[] args) throws ParseException {
        return parseCommandLineOptions(args);
    }

    /**
     * Parse the command line parameters given by the user.
     *
     * @param args - The arguments submitted by the user.
     * @return
     * @throws ParseException
     */
    private CommandLine parseCommandLineOptions(String[] args) throws ParseException {
        CommandLineParser parser = new DefaultParser();
        Options options = initializeCommandlineOptions();

        return parser.parse(options, args);
    }

    /**
     * Initialize the default CLI option configurations.
     *
     * @return A list of CLI options.
     */
    private Options initializeCommandlineOptions() {
        Options commands = new Options();

        //Add task
        Option addTask = new Option("new", true, "Create a new task. Optionally start the task.");
        addTask.setArgs(2);
        addTask.setArgName("new");

        //Start existing task
        Option startTask = new Option("start", true, "Start an existing task.");
        startTask.setArgs(1);
        startTask.setArgName("start");

        //Remove task
        Option removeTask = new Option("remove", true, "Remove (delete) a task from the task manager.");
        removeTask.setArgs(1);
        removeTask.setArgName("remove");

        //List tasks
        Option listTasks = new Option("list", false, "List all tasks with the length of time spent on each.");
        listTasks.setArgName("list");

        //Help formatter
        Option help = new Option("help", false, "List command line options.");
        help.setArgName("help");

        commands.addOption(addTask);
        commands.addOption(removeTask);
        commands.addOption(startTask);
        commands.addOption(listTasks);
        commands.addOption(help);

        return commands;
    }

    /**
     * Print CLI options to terminal.
     *
     * @param options - A list of command line options.
     */
    private void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("Task Manager", options);
        System.out.println("\n\n");
    }

    /**
     * Exit the program with a message.
     *
     * @param message - The message to be rendered in the console.
     */
    private void die(String message) {
        System.out.println(message);
        System.exit(0);
    }

    /**
     * Exit the program with a list of all available CLI options.
     */
    private void dieWithHelper() {
        printHelp(this.options);
        System.exit(0);
    }

    /**
     * Exit the program with a message and list all available CLI options.
     *
     * @param message - The message to be rendered in the console.
     */
    private void dieWithHelper(String message) {
        System.out.println(message);
        printHelp(this.options);
        System.exit(0);
    }
}
