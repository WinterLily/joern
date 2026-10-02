import 'package:test/test.dart';

import '../../src/test/resources/semantics/extension_type_patterns.dart'
    as patterns;

void main() {
  test(
    'object patterns constrain inherited getters to the matched representation',
    () {
      for (final text in ['first', 'second']) {
        final store = patterns.View<patterns.Store>(patterns.Store(text));
        final other = patterns.View<patterns.OtherStore>(
          patterns.OtherStore(text),
        );
        for (final select in [patterns.direct, patterns.plain, patterns.own]) {
          patterns.trace.clear();
          expect(select(store), text);
          expect(patterns.trace, ['store']);
          patterns.trace.clear();
          expect(select(other), 'miss');
          expect(patterns.trace, isEmpty);
        }
        patterns.trace.clear();
        expect(patterns.other(other), text);
        expect(patterns.trace, ['other']);
        patterns.trace.clear();
        expect(patterns.other(store), 'miss');
        expect(patterns.trace, isEmpty);
      }
    },
  );
  test('nested and record patterns retain instantiated field results', () {
    for (final text in ['first', 'second']) {
      for (final select in [patterns.nested, patterns.captured]) {
        patterns.trace.clear();
        expect(select(patterns.Holder(patterns.Store(text))), text);
        expect(patterns.trace, ['holder', 'store']);
      }
      patterns.trace.clear();
      expect(patterns.record((patterns.View(patterns.Store(text)),)), text);
      expect(patterns.trace, ['store']);
    }
  });
  test(
    'alternative typed patterns share only the actual receiver getter result',
    () {
      for (final text in ['first', 'second']) {
        patterns.trace.clear();
        expect(
          patterns.alternatives(patterns.View(patterns.Store(text))),
          text,
        );
        expect(patterns.trace, ['store']);
        patterns.trace.clear();
        expect(
          patterns.alternatives(patterns.View(patterns.OtherStore(text))),
          text,
        );
        expect(patterns.trace, ['other']);
      }
    },
  );
  test('cached field views preserve their own generic substitutions', () {
    for (final text in ['first', 'second']) {
      patterns.trace.clear();
      expect(
        patterns.nestedAlternatives(patterns.Holder(patterns.Store(text))),
        text,
      );
      expect(patterns.trace, ['holder', 'store']);
      patterns.trace.clear();
      expect(
        patterns.nestedAlternatives(patterns.Holder(patterns.OtherStore(text))),
        text,
      );
      expect(patterns.trace, ['holder', 'other']);
    }
  });
}
