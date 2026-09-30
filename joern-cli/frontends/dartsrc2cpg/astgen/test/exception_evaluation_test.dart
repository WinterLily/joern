// Abrupt cleanup is intentional: these cases check exit replacement.
// ignore_for_file: control_flow_in_finally

import 'package:test/test.dart';

void main() {
  test(
    'finally preserves or replaces a pending return and runs inner cleanup first',
    () {
      for (final replace in [false, true]) {
        final trace = <String>[];
        String run(String value) {
          try {
            try {
              trace.add('body');
              return value;
            } finally {
              trace.add('inner');
            }
          } finally {
            trace.add('outer');
            if (replace) return 'constant';
          }
        }

        expect(run('input'), replace ? 'constant' : 'input');
        expect(trace, ['body', 'inner', 'outer']);
      }
      String replacedByThrow() {
        try {
          return 'input';
        } finally {
          throw StateError('cleanup');
        }
      }

      expect(replacedByThrow, throwsStateError);
    },
  );
  test('break, continue and rethrow execute their enclosing cleanup', () {
    final trace = <String>[];
    for (var i = 0; i < 3; i++) {
      try {
        trace.add('body:$i');
        if (i == 0) continue;
        break;
      } finally {
        trace.add('cleanup:$i');
      }
    }
    expect(trace, ['body:0', 'cleanup:0', 'body:1', 'cleanup:1']);
    trace.clear();
    void fail() {
      try {
        try {
          throw StateError('initial');
        } on StateError {
          trace.add('catch');
          rethrow;
        } finally {
          trace.add('inner');
        }
      } finally {
        trace.add('outer');
      }
    }

    expect(fail, throwsStateError);
    expect(trace, ['catch', 'inner', 'outer']);
  });
}
