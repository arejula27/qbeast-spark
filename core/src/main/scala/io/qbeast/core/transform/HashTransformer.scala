package io.qbeast.core.transform

import io.qbeast.core.model.QDataType

object HashTransformer extends TransformerType {
  override def transformerSimpleName: String = "hashing"

}

case class HashTransformer(
    columnName: String,
    override val dataType: QDataType,
    override val optionalNullValue: Option[Any])
    extends Transformer {
  override protected def transformerType: TransformerType = HashTransformer

  override def makeTransformation(columnStats: ColumnStats): Transformation = {
    optionalNullValue match {
      case Some(value) => HashTransformation(value)
      case None => HashTransformation()
    }
  }

}
