import 'package:test/test.dart';

final _events = <String>[];
String _initial(String name) {
  _events.add(name);
  return name;
}

String _writtenBeforeRead = _initial('skipped');
final String _readTwice = _initial('once');
int _attempts = 0;
String _retryInitializer() {
  _events.add('attempt');
  if (_attempts++ == 0) throw StateError('retry');
  return 'success';
}

String _retried = _retryInitializer();

class _Holder {
  static String written = _initial('static skipped');
  static final String read = _initial('static once');
}

int _finalAttempts = 0;
int _finalInitializer() {
  final attempt = ++_finalAttempts;
  if (attempt == 1) expect(_finalValue, 2);
  return attempt;
}

final int _finalValue = _finalInitializer();

int _mutableAttempts = 0;
int _mutableInitializer() {
  final attempt = ++_mutableAttempts;
  if (attempt == 1) expect(_mutableValue, 2);
  return attempt;
}

int _mutableValue = _mutableInitializer();

void main() {
  test(
    'reentrant initialization rejects a second final write but permits a mutable write',
    () {
      expect(
        () => _finalValue,
        throwsA(
          predicate(
            (Object error) =>
                error.toString().startsWith('LateInitializationError:'),
          ),
        ),
      );
      expect(_finalValue, 2);
      expect(_finalAttempts, 2);
      expect(_mutableValue, 1);
      expect(_mutableValue, 1);
      expect(_mutableAttempts, 2);
    },
  );

  test(
    'ordinary globals and statics initialize on first read and retry failures',
    () {
      expect(_events, isEmpty);
      _writtenBeforeRead = 'assigned';
      _Holder.written = 'assigned';
      expect(_writtenBeforeRead, 'assigned');
      expect(_Holder.written, 'assigned');
      expect(_events, isEmpty);
      expect(_readTwice, 'once');
      expect(_readTwice, 'once');
      expect(_Holder.read, 'static once');
      expect(_Holder.read, 'static once');
      expect(() => _retried, throwsStateError);
      expect(_retried, 'success');
      expect(_retried, 'success');
      expect(_events, ['once', 'static once', 'attempt', 'attempt']);
    },
  );
}
