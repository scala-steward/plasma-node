package co.topl.genus

package object typeclasses {

  trait Implicits
      extends MongoFilterInstances
      with MongoFilter.ToMongoFilterOps
      with MongoSort.ToMongoSortOps
      with MongoSortInstances
      with FunctionKInstances
      with Validation.ToValidationOps
      with ValidationInstances
      with FunctorInstances
      with Transform.ToTransformOps
      with TransformInstances
      with WithMaxBlockHeight.ToWithMaxBlockHeightOps
      with WithMaxBlockHeightInstances

  object implicits extends Implicits
}
