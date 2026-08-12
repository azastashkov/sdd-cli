package sdd.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "sdd",
        description = "Spec-Driven Development pipeline for multi-repo estates",
        mixinStandardHelpOptions = true,
        version = "sdd 0.1.0",
        subcommands = {DoctorCommand.class, IndexCommand.class, PlanCommand.class, GraphCommand.class})
public final class SddCli {
    public static void main(String[] args) {
        System.exit(new CommandLine(new SddCli()).execute(args));
    }
}
