package dev.bingo;

public record ModelChoice(Agent agent, String model) {
    public String display() { return "[" + agent.label() + "] " + model; }
}
