import 'package:test/test.dart';

import '../../src/test/resources/semantics/override_arguments.dart' as fixture;

void main() {
  test('overrides bind named arguments and supply their own defaults', () {
    expect(fixture.omitted(fixture.Ordered()), 'ordered');
    expect(fixture.omitted(fixture.ExtraFirst()), 'extra first');
    expect(fixture.boundOmitted(fixture.Ordered()), 'ordered');
    expect(fixture.boundOmitted(fixture.ExtraFirst()), 'extra first');
    expect(fixture.omitted(fixture.ExtraResult()), 'additional result');
    expect(fixture.boundOmitted(fixture.ExtraResult()), 'additional result');
    expect(fixture.literalSupplied(fixture.Ordered()), 'abstract');
    expect(fixture.literalSupplied(fixture.ExtraFirst()), 'abstract');
    expect(fixture.DirectDefault().mixed(), 'base');
    expect(fixture.AppliedDefault().mixed(), 'prefix');
    for (final input in ['', 'input']) {
      for (final ignored in ['', 'ignored']) {
        for (final receiver in [fixture.Ordered(), fixture.ExtraFirst()]) {
          expect(fixture.explicit(receiver, input, ignored), input);
          expect(fixture.bound(receiver, input, ignored), input);
        }
        final writer = fixture.ReorderedWriter();
        expect(fixture.written(writer, input), input);
        expect(fixture.unrelated(writer, input), 'other');
      }
    }
  });
}
