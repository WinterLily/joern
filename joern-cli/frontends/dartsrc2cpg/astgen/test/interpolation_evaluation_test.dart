import 'package:test/test.dart';

import '../../src/test/resources/semantics/interpolation_order.dart' as fixture;

void main() {
  setUp(fixture.trace.clear);

  test('interpolation evaluates values before conversions', () {
    expect(fixture.interpolate(), '1:2');
    expect(fixture.trace, ['value:1', 'value:2', 'string:1', 'string:2']);
  });
  test(
    'adjacent interpolated literals share evaluation and conversion phases',
    () {
      expect(fixture.adjacent(), '1:2');
      expect(fixture.trace, ['value:1', 'value:2', 'string:1', 'string:2']);
    },
  );
  test('directly nested interpolation shares the conversion phase', () {
    expect(fixture.nested(), '1:2');
    expect(fixture.trace, ['value:1', 'value:2', 'string:1', 'string:2']);
  });
  test(
    'a call boundary completes its string argument before later expressions',
    () {
      expect(fixture.boundary(), '1:2');
      expect(fixture.trace, ['value:1', 'string:1', 'value:2', 'string:2']);
    },
  );
  test('expression failure prevents every outer conversion', () {
    expect(fixture.expressionFailure, throwsStateError);
    expect(fixture.trace, ['value:1', 'value:0']);
  });
  test('conversion failure follows evaluation of every expression', () {
    expect(fixture.conversionFailure, throwsStateError);
    expect(fixture.trace, ['value:-1', 'value:2', 'string:-1']);
  });
  test(
    'nullable values produce null text without invoking user conversion',
    () {
      expect(fixture.nullable(null, null), 'null:null');
      expect(fixture.trace, isEmpty);
      expect(fixture.nullable(fixture.Render(3), 'text'), '3:text');
      expect(fixture.trace, ['string:3']);
    },
  );
}
