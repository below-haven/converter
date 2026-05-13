package eu.ponei.converter;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
	private final Map<String, Command> commands;

	Main(List<Command> commands) {
		this.commands = new LinkedHashMap<>();

		for (Command command : commands) {
			this.commands.put(command.name(), command);
		}
	}

	public static void main(String[] args) {
		int status = new Main(List.of(new TinyToEnigmaCommand()))
				.run(Arrays.asList(args), System.out, System.err);
		System.exit(status);
	}

	int run(List<String> args, PrintStream out, PrintStream err) {
		if (args.isEmpty()) {
			printUsage(err);
			return 2;
		}

		Command command = commands.get(args.getFirst());
		if (command == null) {
			err.println("Unknown command: " + args.getFirst());
			printUsage(err);
			return 2;
		}

		try {
			return command.run(args.subList(1, args.size()), out, err);
		} catch (ConversionException e) {
			err.println(e.getMessage());
			return 1;
		} catch (Exception e) {
			err.println("Unexpected failure: " + e.getMessage());
			return 1;
		}
	}

	private void printUsage(PrintStream err) {
		err.println("Usage:");

		for (Command command : commands.values()) {
			err.println("  " + command.usage());
		}
	}
}
