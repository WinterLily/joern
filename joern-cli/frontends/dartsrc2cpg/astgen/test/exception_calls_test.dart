import 'package:test/test.dart';

import '../../src/test/resources/semantics/exception_calls.dart' as fixture;

void main() {
  test(
    'wrappers and rethrow preserve distinct return and exception values',
    () {
      final input = Object();
      final normal = Object();
      for (final invoke in [fixture.catchForward, fixture.catchRethrow]) {
        expect(invoke(input, normal, true), same(input));
        expect(invoke(input, normal, false), same(normal));
      }
    },
  );

  test('handled and replaced exceptions do not escape their callee', () {
    final input = Object();
    expect(fixture.catchSuppressed(input), 'constant');
    expect(fixture.catchHandled(input), 'constant');
    expect(fixture.independentCalls(input), 'constant');
    expect(fixture.catchStack(input), isA<StackTrace>());
    expect(
      (fixture.catchConstructed(input) as fixture.Failure).value,
      same(input),
    );
  });
}
