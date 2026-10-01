class Data {}

typedef Poly = T Function<T extends Data>(T);
typedef Both = (T Function<T extends Data>(T), T Function<T extends num>(T));
typedef Higher<F extends T Function<T extends Data>(T)> = F;

T identity<T extends Data>(T value) => value;
T numeric<T extends num>(T value) => value;

class Holder {
  T Function<T extends Data>(T) get callback => identity;
}

(Data, num) callbacks(
  T first<T extends Data>(T value),
  T Function<T extends num>(T) second,
  Data value,
) => (first(value), second(7));
