import 'dart:async';

import 'package:test/test.dart';

void main() {
  test(
    'completion retains error and stack trace on the selected future',
    () async {
      final failed = Completer<String>();
      final successful = Completer<String>();
      final error = StateError('input');
      final trace = StackTrace.fromString('supplied trace');
      final observed = <Object>[];
      final handled = failed.future.then(
        (value) => value,
        onError: (Object received, StackTrace stack) {
          observed.addAll([received, stack]);
          return 'handled';
        },
      );
      successful.complete('independent');
      failed.completeError(error, trace);
      expect(await handled, 'handled');
      expect(await successful.future, 'independent');
      expect(identical(observed[0], error), isTrue);
      expect(identical(observed[1], trace), isTrue);
      expect(() => failed.complete('replacement'), throwsStateError);
    },
  );

  test('a zone may replace an error before completion stores it', () async {
    final original = StateError('input');
    final replacement = StateError('replacement');
    final observed = await runZoned(
      () async {
        final completer = Completer<String>();
        final handled = completer.future.then<Object>(
          (value) => value,
          onError: (Object error, StackTrace stack) => error,
        );
        completer.completeError(original, StackTrace.empty);
        return handled;
      },
      zoneSpecification: ZoneSpecification(
        errorCallback: (self, parent, zone, error, stack) =>
            AsyncError(replacement, StackTrace.empty),
      ),
    );
    expect(identical(observed, replacement), isTrue);
    expect(identical(observed, original), isFalse);
  });
}
