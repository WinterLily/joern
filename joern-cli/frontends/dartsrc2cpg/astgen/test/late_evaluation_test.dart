import 'package:test/test.dart';

class Lazy {
  final int? Function() initialize;
  late final int? once = initialize();
  late int? writable = initialize();
  late int assigned;
  late final int finalAssigned;
  Lazy(this.initialize);
}

void main() {
  test(
    'late local initializers capture the value at first read and cache it',
    () {
      var input = 1;
      var calls = 0;
      late final value = (() {
        calls++;
        return input + 1;
      })();
      input = 2;
      expect(calls, 0);
      expect(value, 3);
      input = 9;
      expect(value, 3);
      expect(calls, 1);
      late int? assigned;
      expect(() => assigned, throwsA(isA<Error>()));
      assigned = null;
      expect(assigned, isNull);
    },
  );
  test(
    'late initialization is per object, caches null, and retries failures',
    () {
      var calls = 0;
      final first = Lazy(() {
        calls++;
        return null;
      });
      final second = Lazy(() {
        calls++;
        return 2;
      });
      expect(calls, 0);
      expect(first.once, isNull);
      expect(first.once, isNull);
      expect(calls, 1);
      expect(second.once, 2);
      expect(calls, 2);
      var attempts = 0;
      final retry = Lazy(() {
        if (++attempts == 1) throw StateError('retry');
        return 7;
      });
      expect(() => retry.once, throwsStateError);
      expect(retry.once, 7);
      expect(retry.once, 7);
      expect(attempts, 2);
    },
  );
  test(
    'a write can bypass lazy initialization but late final rejects a second write',
    () {
      final value = Lazy(() => throw StateError('must not initialize'));
      value.writable = null;
      expect(value.writable, isNull);
      expect(() => value.assigned, throwsA(isA<Error>()));
      value.assigned = 3;
      value.assigned = 4;
      expect(value.assigned, 4);
      value.finalAssigned = 5;
      expect(() {
        value.finalAssigned = 6;
      }, throwsA(isA<Error>()));
      expect(value.finalAssigned, 5);
    },
  );
}
