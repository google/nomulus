package google.registry.monitoring.blackbox;

public class ProbingFlows {

  public static void main(String[] args) {
    ProbingSequence sequence = new ProbingSequence
        .Builder()
        .addStep(new ProbingStep())
        .build();

    sequence.start();


  }
}
