package org.apache.flink.statefun.e2e.smoke;

import static org.apache.flink.statefun.e2e.smoke.ProtobufUtils.unpack;

import com.google.protobuf.Any;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.apache.flink.statefun.e2e.smoke.generated.Command;
import org.apache.flink.statefun.e2e.smoke.generated.Commands;
import org.apache.flink.statefun.e2e.smoke.generated.SourceCommand;
import org.apache.flink.statefun.e2e.smoke.generated.VerificationResult;
import org.apache.flink.statefun.sdk.AsyncOperationResult;
import org.apache.flink.statefun.sdk.Context;
import org.apache.flink.statefun.sdk.FunctionType;
import org.apache.flink.statefun.sdk.state.PersistedValue;

public final class CommandInterpreter {
  private final AsyncCompleter asyncCompleter;
  private final Ids ids;
  private static final Duration sendAfterDelay = Duration.ofMillis(1);

  public CommandInterpreter(Ids ids) {
    this.asyncCompleter = new AsyncCompleter();
    asyncCompleter.start();
    this.ids = Objects.requireNonNull(ids);
  }

  public void interpret(PersistedValue<Long> state, Context context, Object message) {
    if (message instanceof AsyncOperationResult) {
      @SuppressWarnings("unchecked")
      AsyncOperationResult<Commands, ?> res = (AsyncOperationResult<Commands, ?>) message;
      interpret(state, context, res.metadata());
      return;
    }
    if (!(message instanceof Any)) {
      throw new IllegalArgumentException("wtf " + message);
    }
    Any any = (Any) message;
    if (any.is(SourceCommand.class)) {
      SourceCommand sourceCommand = unpack(any, SourceCommand.class);
      interpret(state, context, sourceCommand.getCommands());
    } else if (any.is(Commands.class)) {
      Commands commands = unpack(any, Commands.class);
      interpret(state, context, commands);
    } else {
      throw new IllegalArgumentException("Unknown message type " + any.getTypeUrl());
    }
  }

  private void interpret(PersistedValue<Long> state, Context context, Commands command) {
    for (Command cmd : command.getCommandList()) {
      if (cmd.hasIncrement()) {
        modifyState(state, context, cmd.getIncrement());
      } else if (cmd.hasAsyncOperation()) {
        registerAsyncOps(state, context, cmd.getAsyncOperation());
      } else if (cmd.hasSend()) {
        send(state, context, cmd.getSend());
      } else if (cmd.hasSendAfter()) {
        sendAfter(state, context, cmd.getSendAfter());
      } else if (cmd.hasSendEgress()) {
        sendEgress(state, context, cmd.getSendEgress());
      } else if (cmd.hasVerify()) {
        verify(state, context, cmd.getVerify());
      }
    }
  }

  private void verify(
      PersistedValue<Long> state,
      @SuppressWarnings("unused") Context context,
      Command.Verify verify) {
    int selfId = Integer.parseInt(context.self().id());
    long actual = state.getOrDefault(0L);
    long expected = verify.getExpected();
    VerificationResult verificationResult =
        VerificationResult.newBuilder()
            .setId(selfId)
            .setActual(actual)
            .setExpected(expected)
            .build();
    context.send(Constants.VERIFICATION_RESULT, Any.pack(verificationResult));
  }

  private void sendEgress(
      @SuppressWarnings("unused") PersistedValue<Long> state,
      Context context,
      @SuppressWarnings("unused") Command.SendEgress sendEgress) {
    context.send(Constants.OUT, Any.getDefaultInstance());
  }

  private void sendAfter(
      @SuppressWarnings("unused") PersistedValue<Long> state,
      Context context,
      Command.SendAfter send) {
    FunctionType functionType = Constants.FN_TYPE;
    String id = ids.idOf(send.getTarget());
    context.sendAfter(sendAfterDelay, functionType, id, Any.pack(send.getCommands()));
  }

  private void send(
      @SuppressWarnings("unused") PersistedValue<Long> state, Context context, Command.Send send) {
    FunctionType functionType = Constants.FN_TYPE;
    String id = ids.idOf(send.getTarget());
    context.send(functionType, id, Any.pack(send.getCommands()));
  }

  private void registerAsyncOps(
      @SuppressWarnings("unused") PersistedValue<Long> state,
      Context context,
      Command.AsyncOperation asyncOperation) {
    CompletableFuture<Boolean> future =
        asyncOperation.getFailure()
            ? asyncCompleter.successfulFuture()
            : asyncCompleter.failedFuture();

    Commands next = asyncOperation.getResolvedCommands();
    context.registerAsyncOperation(next, future);
  }

  private void modifyState(
      PersistedValue<Long> state,
      @SuppressWarnings("unused") Context context,
      @SuppressWarnings("unused") Command.IncrementState incrementState) {
    state.updateAndGet(n -> n == null ? 1 : n + 1);
  }
}
