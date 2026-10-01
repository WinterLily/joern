import 'package:test/test.dart';
import '../../src/test/resources/semantics/default_argument_interactions.dart'
    as fixture;
import '../../src/test/resources/semantics/default_argument_implementations.dart'
    as implementations;

void main() {
  test(
    'defaults preserve evaluation, separate invocations and captured receivers',
    () {
      for (final input in ['', 'input']) {
        fixture.events.clear();
        expect(fixture.traced(input), input);
        expect(fixture.events, ['receiver', 'ignored', 'first']);
        fixture.events.clear();
        expect(fixture.tracedIgnored(input), 'constant');
        expect(fixture.events, ['receiver', 'ignored', 'first']);
        fixture.events.clear();
        expect(fixture.tracedBound(input), input);
        expect(fixture.events, [
          'receiver',
          'ignored1',
          'first1',
          'ignored2',
          'first2',
        ]);
        for (final replacement in ['', 'replacement']) {
          expect(fixture.capturedValues(input, replacement), replacement);
          expect(fixture.rebound(input, replacement), input);
          expect(fixture.nestedBound(input, replacement), input);
          expect(fixture.nestedSupplied(input, replacement), input);
        }
        final named = implementations.NamedOverride();
        expect(fixture.repeated(named, input), 'constant');
        expect(fixture.repeatedInput(named, input), input);
        expect(fixture.differingMasks(named, input), 'override');
        expect(
          fixture.positionalSupplied(
            implementations.PositionalOverride(),
            input,
          ),
          input,
        );
        expect(
          fixture.positionalSupplied(implementations.PositionalExtra(), input),
          'additional positional',
        );
      }
      expect(
        fixture.captured(
          implementations.NamedState('left'),
          implementations.NamedState('right'),
        ),
        'right',
      );
      expect(
        fixture.positionalOmitted(implementations.PositionalOverride()),
        'positional',
      );
      expect(
        fixture.positionalBound(implementations.PositionalOverride()),
        'positional',
      );
      expect(
        fixture.positionalOmitted(implementations.PositionalExtra()),
        'additional positional',
      );
      expect(
        fixture.positionalBound(implementations.PositionalExtra()),
        'additional positional',
      );
    },
  );
}
