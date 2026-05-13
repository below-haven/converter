package eu.ponei.converter;

import java.io.PrintStream;
import java.util.List;

interface Command {
	String name();

	String usage();

	int run(List<String> args, PrintStream out, PrintStream err) throws Exception;
}
