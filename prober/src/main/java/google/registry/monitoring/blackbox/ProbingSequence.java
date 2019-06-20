package google.registry.monitoring.blackbox;

class ProbingSequence {
  private ProbingStep firstStep;

  public void start() {
    // create a new unique token;
    Token<String> token = new TestToken<>();
    firstStep.accept(token);
  }

  /**
   * Builder that sequentially adds steps
   */
  static class Builder {
    private ProbingStep currentStep;
    private ProbingStep firstStep;

    public Builder addStep(ProbingStep step) {
      if (currentStep == null) {
        firstStep = step;
      } else {
        currentStep.nextStep(step);
      }

      currentStep = step;
      return this;

    }

    public ProbingSequence build() {
      currentStep.nextStep(firstStep);
      return new ProbingSequence(this.firstStep);
    }

  }

  private ProbingSequence(ProbingStep firstStep) {
    this.firstStep = firstStep;
  }
}

