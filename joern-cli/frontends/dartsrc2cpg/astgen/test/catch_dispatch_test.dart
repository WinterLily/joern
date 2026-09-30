import 'package:test/test.dart';

import '../../src/test/resources/semantics/catch_dispatch.dart' as fixture;

void main() {
  setUp(fixture.trace.clear);

  test('typed clauses select the first match and finally runs afterward', () {
    final cases = [
      (const FormatException('format input'), 'format input', 'format'),
      (StateError('state input'), 'state input', 'state'),
      ('plain input', 'plain input', 'fallback'),
    ];
    for (final (input, output, handler) in cases) {
      fixture.trace.clear();
      expect(fixture.choose(input), output);
      expect(fixture.trace, [handler, 'finally']);
    }
    fixture.trace.clear();
    expect(fixture.overlapping(const FormatException('input')), 'first');
    expect(fixture.trace, ['first']);
  });

  test(
    'unmatched clauses propagate and rethrow preserves the caught object',
    () {
      final error = StateError('input');
      expect(() => fixture.filtered(error), throwsA(same(error)));
      expect(fixture.filtered(const FormatException('handled')), 'handled');
      expect(fixture.nested('input'), 'input');
      expect(fixture.trace, ['inner', 'outer']);
      fixture.trace.clear();
      expect(identical(fixture.nested(error), error), isTrue);
      expect(fixture.trace, ['outer']);
    },
  );

  test(
    'caught values preserve identity while replacement and later handlers stay independent',
    () {
      final input = Object();
      expect(identical(fixture.caughtValue(input), input), isTrue);
      expect(
        (fixture.caughtConstructed(input) as fixture.Failure).value,
        same(input),
      );
      expect(identical(fixture.forwardedRethrow(input), input), isTrue);
      expect(fixture.caughtTrace(input), isA<StackTrace>());
      expect(fixture.caughtReplacement(input), 'constant');
      expect(fixture.independentHandler(input), 'constant');
    },
  );

  test('a failure handled inside cleanup preserves the pending return', () {
    expect(fixture.preservedReturn('input'), 'input');
    expect(fixture.trace, ['handled']);
  });

  test('cleanup preserves or replaces the pending exception value', () {
    final input = Object();
    expect(fixture.throughFinally(input), same(input));
    expect(fixture.trace, ['normal']);
    fixture.trace.clear();
    expect(fixture.throughHandledCleanup(input), same(input));
    expect(fixture.trace, ['cleanup']);
    expect(fixture.replacedInCleanup(input), 'constant');
    expect(fixture.returnedFromCleanup(input), 'constant');
  });

  test('only jumps leaving cleanup discard its pending exception', () {
    final input = Object();
    expect(fixture.breakFromCleanup(input), 'constant');
    expect(fixture.continueFromCleanup(input), 'constant');
    expect(fixture.trace, ['after break', 'after continue']);
    expect(fixture.localBreakInCleanup(input), same(input));
    expect(fixture.localContinueInCleanup(input), same(input));
  });
}
