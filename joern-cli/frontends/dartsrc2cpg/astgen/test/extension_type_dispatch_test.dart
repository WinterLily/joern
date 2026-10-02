import 'package:test/test.dart';

import '../../src/test/resources/semantics/extension_type_dispatch.dart'
    as dispatch;
import '../../src/test/resources/semantics/synchronous_iteration.dart'
    as iteration;

void main() {
  test(
    'extension types invoke inherited members on representation objects',
    () {
      final store = dispatch.Store('initial');
      final generic = dispatch.GenericView<dispatch.Store>(store);
      dispatch.trace.clear();
      expect(dispatch.direct(dispatch.View(store)), 'initial');
      expect(
        dispatch.nested(dispatch.NestedView(dispatch.View(store))),
        'initial',
      );
      expect(dispatch.concrete(generic), 'initial');
      expect(dispatch.bounded(generic), 'initial');
      expect(dispatch.nullable(generic), 'initial');
      expect(dispatch.trace, List.filled(5, 'read'));
      dispatch.trace.clear();
      expect(dispatch.nullable(null), isNull);
      expect(dispatch.trace, isEmpty);
      expect(dispatch.getter(generic), 'initial');
      dispatch.setter(generic, 'assigned');
      expect(store.items.single, 'assigned');
      expect(dispatch.index(generic), 'assigned');
      dispatch.indexedSetter(generic, 'indexed');
      expect(store.items.single, 'indexed');
      expect(dispatch.trace, ['get', 'set', 'index', 'index-set']);
      dispatch.trace.clear();
      dispatch.cascade(generic);
      expect(store.items.single, 'changed');
      expect(dispatch.trace, ['read', 'set']);
      dispatch.trace.clear();
      final bound = dispatch.tearOff(generic);
      store.items[0] = 'captured';
      expect(bound(), 'captured');
      expect(dispatch.trace, ['read']);
      dispatch.trace.clear();
      expect(
        dispatch.other(
          dispatch.GenericView<dispatch.OtherStore>(dispatch.OtherStore()),
        ),
        'other',
      );
      expect(dispatch.own(dispatch.Shadow(store)), 'shadow');
      expect(dispatch.trace, isEmpty);
      final independent = dispatch.Store('independent');
      expect(
        dispatch.concrete(dispatch.GenericView<dispatch.Store>(independent)),
        'independent',
      );
      expect(store.items.single, 'captured');
    },
  );

  test('nested extension iteration keeps values and member order', () {
    for (final nested in [false, true]) {
      for (final input in [
        ['first', 'second'],
        ['independent'],
      ]) {
        iteration.trace.clear();
        final view = dispatch.ValuesView(iteration.Values(input));
        expect(
          nested
              ? dispatch.nestedValues(dispatch.NestedValuesView(view))
              : dispatch.wrappedValues(view),
          input,
        );
        expect(iteration.trace, [
          'iterator',
          for (final _ in input) ...['moveNext', 'current'],
          'moveNext',
        ]);
      }
    }
  });

  test(
    'erased iterator and current results keep their original static views',
    () {
      iteration.trace.clear();
      expect(
        dispatch.wrappedCursor(
          dispatch.WrappedCursorValues(['first', 'second']),
        ),
        ['first', 'second'],
      );
      expect(iteration.trace, [
        'moveNext',
        'current',
        'moveNext',
        'current',
        'moveNext',
      ]);
      iteration.trace.clear();
      dispatch.trace.clear();
      expect(
        dispatch.wrappedCurrent(
          dispatch.WrappedCursorValues([
            dispatch.GenericView<dispatch.Store>(dispatch.Store('first')),
            dispatch.GenericView<dispatch.Store>(dispatch.Store('second')),
          ]),
        ),
        ['first', 'second'],
      );
      expect(iteration.trace, [
        'moveNext',
        'current',
        'moveNext',
        'current',
        'moveNext',
      ]);
      expect(dispatch.trace, ['read', 'read']);
    },
  );
}
