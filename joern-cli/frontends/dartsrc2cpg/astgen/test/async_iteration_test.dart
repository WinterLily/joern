import 'dart:async';

import 'package:test/test.dart';

void main() {
  test('await for waits for cancellation on abrupt body exits', () async {
    for (final exit in ['break', 'return', 'throw', 'outer-continue']) {
      final trace = <String>[];
      final cancellationStarted = Completer<void>();
      final releaseCancellation = Completer<void>();
      final error = StateError('body');
      late StreamController<String> controller;
      controller = StreamController<String>(
        onListen: () => scheduleMicrotask(() {
          controller.add('input');
          controller.add('unvisited');
        }),
        onCancel: () async {
          trace.add('cancel:start');
          cancellationStarted.complete();
          await releaseCancellation.future;
          trace.add('cancel:end');
        },
      );
      Future<String> run() async {
        outer:
        for (var i = 0; i < 1; i++) {
          await for (final value in controller.stream) {
            trace.add('body:$value');
            switch (exit) {
              case 'break':
                break;
              case 'return':
                return value;
              case 'throw':
                throw error;
              case 'outer-continue':
                continue outer;
            }
            break;
          }
          trace.add('after-loop');
        }
        trace.add('after-outer');
        return 'done';
      }

      var completed = false;
      Object? caught;
      final result = run().then(
        (value) {
          completed = true;
          return value;
        },
        onError: (Object failure) {
          completed = true;
          caught = failure;
          return 'failed';
        },
      );
      await cancellationStarted.future;
      // Let completion observers run while cancellation remains blocked.
      await Future<void>.delayed(Duration.zero);
      expect(completed, isFalse, reason: exit);
      expect(trace, ['body:input', 'cancel:start'], reason: exit);
      releaseCancellation.complete();
      expect(
        await result,
        exit == 'return' ? 'input' : (exit == 'throw' ? 'failed' : 'done'),
        reason: exit,
      );
      expect(caught, exit == 'throw' ? same(error) : isNull, reason: exit);
      expect(trace, [
        'body:input',
        'cancel:start',
        'cancel:end',
        if (exit == 'break') 'after-loop',
        if (exit == 'break' || exit == 'outer-continue') 'after-outer',
      ], reason: exit);
    }
  });

  test('local continue retains the await for subscription', () async {
    final trace = <String>[];
    late StreamController<int> controller;
    controller = StreamController<int>(
      onListen: () => scheduleMicrotask(() {
        controller.add(0);
        controller.add(1);
        controller.close();
      }),
      onCancel: () => trace.add('cancel'),
    );
    loop:
    await for (final value in controller.stream) {
      trace.add('body:$value');
      if (value == 0) continue loop;
      trace.add('tail:$value');
    }
    expect(trace, ['body:0', 'body:1', 'tail:1', 'cancel']);
  });

  test('cancellation failure replaces the pending body exit', () async {
    for (final exit in ['break', 'return', 'throw']) {
      final bodyError = StateError('body');
      final cancellationError = StateError('cancel');
      final trace = <String>[];
      late StreamController<String> controller;
      controller = StreamController<String>(
        onListen: () => scheduleMicrotask(() => controller.add('input')),
        onCancel: () {
          trace.add('cancel');
          return Future<void>.error(cancellationError);
        },
      );
      Future<String> run() async {
        await for (final value in controller.stream) {
          trace.add(value);
          if (exit == 'return') return value;
          if (exit == 'throw') throw bodyError;
          break;
        }
        trace.add('after');
        return 'done';
      }

      await expectLater(run(), throwsA(same(cancellationError)), reason: exit);
      expect(trace, ['input', 'cancel'], reason: exit);
    }
  });

  test(
    'await for collection elements cancel before propagating failure',
    () async {
      final trace = <String>[];
      final cancellationStarted = Completer<void>();
      final releaseCancellation = Completer<void>();
      final error = StateError('element');
      late StreamController<String> controller;
      controller = StreamController<String>(
        onListen: () => scheduleMicrotask(() {
          controller.add('input');
          controller.add('unvisited');
        }),
        onCancel: () async {
          trace.add('cancel:start');
          cancellationStarted.complete();
          await releaseCancellation.future;
          trace.add('cancel:end');
        },
      );
      String element(String value) {
        trace.add('element:$value');
        throw error;
      }

      Future<List<String>> collect() async => [
        await for (final value in controller.stream) element(value),
      ];
      var completed = false;
      final result = expectLater(collect(), throwsA(same(error))).whenComplete(
        () {
          completed = true;
        },
      );
      await cancellationStarted.future;
      await Future<void>.delayed(Duration.zero);
      expect(completed, isFalse);
      expect(trace, ['element:input', 'cancel:start']);
      releaseCancellation.complete();
      await result;
      expect(trace, ['element:input', 'cancel:start', 'cancel:end']);
    },
  );
  test('the cancellation observer detects an unawaited cleanup', () async {
    final cancellationStarted = Completer<void>();
    final releaseCancellation = Completer<void>();
    final cancellationFinished = Completer<void>();
    late StreamController<String> controller;
    controller = StreamController<String>(
      onListen: () => scheduleMicrotask(() => controller.add('input')),
      onCancel: () async {
        cancellationStarted.complete();
        await releaseCancellation.future;
        cancellationFinished.complete();
      },
    );
    Future<String> run() async {
      final iterator = StreamIterator(controller.stream);
      try {
        await iterator.moveNext();
        return iterator.current;
      } finally {
        unawaited(iterator.cancel());
      }
    }

    var completed = false;
    final result = run().then((value) {
      completed = true;
      return value;
    });
    await cancellationStarted.future;
    await Future<void>.delayed(Duration.zero);
    expect(completed, isTrue);
    expect(cancellationFinished.isCompleted, isFalse);
    releaseCancellation.complete();
    expect(await result, 'input');
    await cancellationFinished.future;
  });
}
