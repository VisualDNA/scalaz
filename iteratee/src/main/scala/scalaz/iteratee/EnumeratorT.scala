package scalaz
package iteratee

import effect._

import Iteratee._

trait EnumeratorT[X, E, F[_]] { self =>
  def apply[A]: StepT[X, E, F, A] => IterateeT[X, E, F, A]

  def map[B](f: E => B)(implicit ev: Monad[F]): EnumeratorT[X, B, F] = 
    new EnumeratorT[X, B, F] {
      def apply[A] = { (step: StepT[X, B, F, A]) => 
        iterateeT((EnumerateeT.map[X, E, B, F](f).apply(step) &= self).run(x => err[X, B, F, A](x).value))
      }
    }

  def flatMap[B](f: E => EnumeratorT[X, B, F])(implicit M0: Monad[F]) = 
    new EnumeratorT[X, B, F] {
      def apply[A] = {
        def loop(step: StepT[X, B, F, A]): IterateeT[X, E, F, StepT[X, B, F, A]] = {
          step.fold(
            cont = contf => cont[X, E, F, StepT[X, B, F, A]] {
              (_: Input[E]).map(e => f(e)).fold(
                el    = en => en.apply(step) >>== loop,
                empty = contf(emptyInput) >>== loop,
                eof   = done(step, emptyInput)
              )
            },
            done = (a, _) => done(sdone(a, emptyInput), emptyInput),
            err  = x => err(x)
          )
        }

        (step: StepT[X, B, F, A]) => iterateeT((loop(step) &= self).run(x => err[X, B, F, A](x).value))
      }
    }

  def collect[B](pf: PartialFunction[E, B])(implicit monad: Monad[F]): EnumeratorT[X, B, F] = 
    new EnumeratorT[X, B, F] {
      def apply[A] = { (step: StepT[X, B, F, A]) => 
        iterateeT((EnumerateeT.collect[X, E, B, F](pf).apply(step) &= self).run(x => err[X, B, F, A](x).value))
      }
    }
}

trait EnumeratorTInstances0 {
  implicit def enumeratorTSemigroup[X, E, F[_]](implicit F0: Bind[F]): Semigroup[EnumeratorT[X, E, F]] = new EnumeratorTSemigroup[X, E, F] {
    implicit def F = F0
  }
}

trait EnumeratorTInstances extends EnumeratorTInstances0 {
  implicit def enumeratorTMonoid[X, E, F[_]](implicit F0: Monad[F]): Monoid[EnumeratorT[X, E, F]] = new EnumeratorTMonoid[X, E, F] {
    implicit def F = F0
  }

  implicit def enumeratorTMonad[X, F[_]](implicit M0: Monad[F]): Monad[({type λ[α]=EnumeratorT[X, α, F]})#λ] = new EnumeratorTMonad[X, F] {
    implicit def M = M0
  }

  implicit def enumeratorTMonadTrans[X]: MonadTrans[({ type λ[β[_], α] = EnumeratorT[X, α, β] })#λ] = new MonadTrans[({ type λ[β[_], α] = EnumeratorT[X, α, β] })#λ] {
    def liftM[G[_]: Monad, E](ga: G[E]): EnumeratorT[X, E, G] = new EnumeratorT[X, E, G] {
      def apply[A] = (s: StepT[X, E, G, A]) => iterateeT(Monad[G].bind(ga) { e => s.mapCont(k => k(elInput(e))).value })
    }

    implicit def apply[G[_]: Monad]: Monad[({type λ[α] = EnumeratorT[X, α, G]})#λ] = enumeratorTMonad[X, G]
  }
}

trait EnumeratorTFunctions {
  def enumerate[E](as: Stream[E]): Enumerator[Unit, E] = enumStream[Unit, E, Id](as)

  /** 
   * An EnumeratorT that is at EOF
   */
  def enumEofT[X, E, F[_] : Pointed]: EnumeratorT[X, E, F] =
    new EnumeratorT[X, E, F] { 
      def apply[A] = _.mapCont(_(eofInput))
    }

  def enumOne[X, E, F[_]: Pointed](e: E): EnumeratorT[X, E, F] = 
    new EnumeratorT[X, E, F] {
      def apply[A] = _.mapCont(_(elInput(e)))
    }

  def enumStream[X, E, F[_] : Monad](xs: Stream[E]): EnumeratorT[X, E, F] = 
    new EnumeratorT[X, E, F] {
      def apply[A] = (s: StepT[X, E, F, A]) => xs match {
        case h #:: t => s.mapCont(k => k(elInput(h)) >>== enumStream(t).apply[A])
        case _       => s.pointI
      }
    }

  def enumIterator[X, E](x: => Iterator[E]): EnumeratorT[X, E, IO] = 
    new EnumeratorT[X, E, IO] { 
      lazy val iter = x
      def apply[A] = (s: StepT[X, E, IO, A]) => 
        s.mapCont(
          k =>
            if (iter.hasNext) {
              val n = iter.next()
              k(elInput(n)) >>== apply[A]
            } else s.pointI
        )
    }

  def enumReader[X](r: => java.io.Reader): EnumeratorT[X, IoExceptionOr[Char], IO] = 
    new EnumeratorT[X, IoExceptionOr[Char], IO] { 
      lazy val reader = r
      def apply[A] = (s: StepT[X, IoExceptionOr[Char], IO, A]) => 
        s.mapCont(
          k => {
            val i = IoExceptionOr(reader.read)
            if (i exists (_ != -1)) k(elInput(i.map(_.toChar))) >>== apply[A]
            else s.pointI
          }
        )
    }

  def enumArray[X, E, F[_]: Monad](a : Array[E], min: Int = 0, max: Option[Int] = None) : EnumeratorT[X, E, F] = 
    new EnumeratorT[X, E, F] {
      private val limit = max.getOrElse(a.length)
      def apply[A] = {
        def loop(pos : Int): StepT[X, E, F, A] => IterateeT[X, E, F, A] = {
          s => 
            s.mapCont(
              k => if (pos == limit) s.pointI
                   else              k(elInput(a(pos))) >>== loop(pos + 1)
            )   
        }

        loop(min)
      }
    }

  def repeat[X, E, F[_] : Monad](e: E): EnumeratorT[X, E, F] = 
    new EnumeratorT[X, E, F] { 
      def apply[A] = (s: StepT[X, E, F, A]) => s.mapCont(_(elInput(e)) >>== apply[A])
    }

  def iterate[X, E, F[_] : Monad](f: E => E, e: E): EnumeratorT[X, E, F] = 
    new EnumeratorT[X, E, F] { 
      def apply[A]: StepT[X, E, F, A] => IterateeT[X, E, F, A] = {
        type StepM = StepT[X, E, F, A]
        type IterateeM = IterateeT[X, E, F, A]

        def checkCont1(z: (E => (StepM => IterateeM)) => E => (Input[E] => IterateeM) => IterateeM, lastState: E): (StepM => IterateeM) = {
          def step: E => (StepM => IterateeM) = {
            state => _.mapCont(k => z(step)(state)(k))
          }

          step(lastState)
        }

        checkCont1(contFactory => state => k => k(elInput(e)) >>== contFactory(f(state)), e)
      }
    }
}

// Instances are mixed in with the IterateeT object
object EnumeratorT extends EnumeratorTFunctions with EnumeratorTInstances

//
// Type class implementation traits
//

private[scalaz] trait EnumeratorTSemigroup[X, E, F[_]] extends Semigroup[EnumeratorT[X, E, F]] {
  implicit def F: Bind[F]

  def append(f1: EnumeratorT[X, E, F], f2: => EnumeratorT[X, E, F]) = 
    new EnumeratorT[X, E, F] {
      def apply[A] = (s: StepT[X, E, F, A]) => f1[A](s) >>== f2[A]
    }
}

private[scalaz] trait EnumeratorTMonoid[X, E, F[_]] extends Monoid[EnumeratorT[X, E, F]] with EnumeratorTSemigroup[X, E, F] {
  implicit def F: Monad[F]

  def zero = new EnumeratorT[X, E, F] {
    def apply[A] = (s: StepT[X, E, F, A]) => s.pointI
  }
}

private[scalaz] trait EnumeratorTFunctor[X, F[_]] extends Functor[({type λ[α]=EnumeratorT[X, α, F]})#λ] {
  implicit def M: Monad[F]
  abstract override def map[A, B](fa: EnumeratorT[X, A, F])(f: A => B): EnumeratorT[X, B, F] = fa.map(f)
}

private[scalaz] trait EnumeratorTPointed[X, F[_]] extends Pointed[({type λ[α]=EnumeratorT[X, α, F]})#λ] with EnumeratorTFunctor[X, F] {
  def point[E](e: => E) = EnumeratorT.enumOne[X, E, F](e)
}

private [scalaz] trait EnumeratorTMonad[X, F[_]] extends Monad[({type λ[α]=EnumeratorT[X, α, F]})#λ] with EnumeratorTPointed[X, F] {
  def bind[A, B](fa: EnumeratorT[X, A, F])(f: A => EnumeratorT[X, B, F]) = fa.flatMap(f)
}