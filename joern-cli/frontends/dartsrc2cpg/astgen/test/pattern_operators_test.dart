import 'package:test/test.dart';

import '../../src/test/resources/semantics/pattern_operators.dart' as fixture;

void main() {
  test(
    'pattern operators use their specified receiver and skip null equality calls',
    () {
      for (final value in ['constant', 'input']) {
        final input = fixture.Comparison(value);
        fixture.trace.clear();
        expect(fixture.constantPattern(input), value == 'constant');
        expect(fixture.trace, ['constant==$value']);
        for (final invoke in [fixture.equalPattern, fixture.binaryEqual]) {
          fixture.trace.clear();
          expect(invoke(input), value == 'constant');
          expect(fixture.trace, ['$value==constant']);
        }
        for (final invoke in [fixture.unequalPattern, fixture.binaryUnequal]) {
          fixture.trace.clear();
          expect(invoke(input), value != 'constant');
          expect(fixture.trace, ['$value==constant']);
        }
        fixture.trace.clear();
        expect(fixture.greaterPattern(input), value.compareTo('constant') > 0);
        expect(fixture.trace, ['$value>constant']);
        for (final invoke in [
          fixture.nullPattern,
          fixture.equalNullPattern,
          fixture.binaryNull,
        ]) {
          fixture.trace.clear();
          expect(invoke(input), false);
          expect(fixture.trace, isEmpty);
          expect(invoke(null), true);
          expect(fixture.trace, isEmpty);
        }
      }
      for (final invoke in [
        fixture.constantPattern,
        fixture.equalPattern,
        fixture.binaryEqual,
      ]) {
        fixture.trace.clear();
        expect(invoke(null), false);
        expect(fixture.trace, isEmpty);
      }
      for (final invoke in [fixture.unequalPattern, fixture.binaryUnequal]) {
        fixture.trace.clear();
        expect(invoke(null), true);
        expect(fixture.trace, isEmpty);
      }
    },
  );
  test('equality evaluates both operands once before checking null', () {
    for (final left in [null, const fixture.Comparison('input')]) {
      for (final right in [null, fixture.marker]) {
        final expected = left == null && right == null;
        final invocation = left != null && right != null
            ? ['input==constant']
            : <String>[];
        fixture.trace.clear();
        expect(fixture.ordered(left, right), expected);
        expect(fixture.trace, ['left', 'right', ...invocation]);
        fixture.trace.clear();
        expect(fixture.dynamicEqual(left, right), expected);
        expect(fixture.trace, invocation);
        fixture.trace.clear();
        expect(fixture.dynamicUnequal(left, right), !expected);
        expect(fixture.trace, invocation);
      }
    }
  });
}
