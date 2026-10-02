import 'pattern_receiver_intersection.dart';

String casted(Object input) => switch (input) {
  Contract(value: var result) as View<Store> => result,
};

String plainCast(Object input) => switch (input) {
  Contract(value: var result) as Store => result,
};

String genericWrapperCast<S extends Store>(Object input) => switch (input) {
  Contract(value: var result) as View<S> => result,
};

String asserted(View<Store>? input) => switch (input) {
  Contract(value: var result)! => result,
};

String plainAssert(Store? input) => switch (input) {
  Contract(value: var result)! => result,
};

String boundedAssert<S extends Store>(View<S>? input) => switch (input) {
  Contract(value: var result)! => result,
};

String scalarCast(Object? input) => switch (input) {
  var result as String => result,
};

String scalarAssert(String? input) => switch (input) {
  var result! => result,
};

T genericCast<T>(Object? input) => switch (input) {
  var result as T => result,
};

T genericAssert<T extends Object>(T? input) => switch (input) {
  var result! => result,
};

String cachedCast(Object input, bool accept) => switch (input) {
  Contract(value: var first) as View<Store> when accept => first,
  Contract(value: var second) as View<Store> => second,
};

String cachedAssert(View<Store>? input, bool accept) => switch (input) {
  Contract(value: var first)! when accept => first,
  Contract(value: var second)! => second,
};

(String, int) recordCast(Object input) => switch (input) {
  (var first, var second) as (String, int) => (first, second),
  _ => throw StateError('unmatched record'),
};

String Function(String) functionCast(Object input) => switch (input) {
  var invoke as String Function(String) => invoke,
};
