import 'package:test/test.dart';

import '../../src/test/resources/semantics/record_fields.dart' as fixture;

void main() {
  test(
    'record fields retain their identities across calls and destructuring',
    () {
      for (final input in ['', 'input']) {
        for (final invoke in [
          fixture.first,
          fixture.namedField,
          fixture.mixedField,
          fixture.mixedPattern,
          fixture.destructuredFirst,
          fixture.nestedFirst,
        ]) {
          expect(invoke(input), input);
        }
        for (final invoke in [
          fixture.second,
          fixture.namedOther,
          fixture.mixedOther,
          fixture.destructuredSecond,
          fixture.namedPattern,
          fixture.nestedSecond,
          fixture.replacedRecord,
          fixture.independentRecord,
        ]) {
          expect(invoke(input), 'constant');
        }
        final trace = <String>[];
        expect(fixture.ordered(input, trace), (
          first: 'constant',
          second: input,
        ));
        expect(trace, ['second', 'first']);
      }
    },
  );
}
