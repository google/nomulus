// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.monitoring.blackbox;

import google.registry.monitoring.blackbox.Tokens.Token;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.AbstractChannel;
import io.netty.channel.EventLoopGroup;

/**
 * Represents Sequence of {@link ProbingSteps} that the Prober performs in order
 *
 * @param <C> Primarily for testing purposes to specify channel type. Usually is {@link NioSocketChannel}
 * but for tests is {@link LocalChannel}
 *
 * <p>Created with {@link Builder} where we specify {@link EventLoopGroup}, {@link AbstractChannel} class type,
 * then sequentially add in the {@link ProbingStep}s in order and mark which one is the first repeated step.</p>
 *
 * <p>{@link ProbingSequence} implicitly points each {@link ProbingStep} to the next one, so once the first one
 * is activated with the requisite {@link Token}, the {@link ProbingStep}s do the rest of the work</p>
 */
public class ProbingSequence<C extends AbstractChannel> {
  private ProbingStep<C> firstStep;

  /** A given {@link Prober} will run each of its {@link ProbingSequence}s with the same given {@link EventLoopGroup} */
  private EventLoopGroup eventGroup;

  /** Each {@link ProbingSequence} houses its own {@link Bootstrap} instance */
  private Bootstrap bootstrap;

  public Bootstrap getBootstrap() {
    return bootstrap;
  }

  public void start(Token token) {
    // calls the first step with input token;
    firstStep.accept(token);
  }

  /**
   * {@link Builder} which takes in {@link ProbingStep}s
   *
   * @param <C> Same specified {@code C} for overall {@link ProbingSequence}
   */
  public static class Builder<C extends AbstractChannel> {
    private ProbingStep<C> currentStep;
    private ProbingStep<C> firstStep;
    private ProbingStep<C> firstSequenceStep;
    private EventLoopGroup eventLoopGroup;
    private Class<C> classType;

    Builder<C> eventLoopGroup(EventLoopGroup eventLoopGroup) {
      this.eventLoopGroup = eventLoopGroup;
      return this;
    }

    Builder<C> addStep(ProbingStep<C> step) {
      if (currentStep == null) {
        firstStep = step;
      } else {
        currentStep.nextStep(step);
      }
      currentStep = step;
      return this;
    }

    /** We take special note of the first repeated step and set pointers in {@link ProbingStep}s appropriately */
    Builder<C> makeFirstRepeated() {
      firstSequenceStep = currentStep;
      return this;
    }
    /** Set the class to be the same as {@code C} */
    public Builder<C> setClass(Class<C> classType) {
      this.classType = classType;
      return this;
    }

    public ProbingSequence<C> build() {
      currentStep.nextStep(firstSequenceStep);
      currentStep.lastStep();
      return new ProbingSequence<>(this.firstStep, this.eventLoopGroup, this.classType);
    }

  }

  /** We point each {@link ProbingStep} to the parent {@link ProbingSequence} so it can access its {@link Bootstrap} */
  private void setParents() {
    ProbingStep<C> currentStep = firstStep.parent(this).nextStep();

    while (currentStep != firstStep) {
      currentStep = currentStep.parent(this).nextStep();
    }
  }
  private ProbingSequence(ProbingStep<C> firstStep, EventLoopGroup eventLoopGroup,
      Class<C> classType) {
    this.firstStep = firstStep;
    this.eventGroup = eventLoopGroup;
    this.bootstrap = new Bootstrap()
        .group(eventGroup)
        .channel(classType);
    setParents();
  }

  @Override
  public String toString() {
    return String.format("ProbingSequence with EventLoopGroup: %s and Bootstrap %s", eventGroup, bootstrap);

  }
}
