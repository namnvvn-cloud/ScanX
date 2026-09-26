import { IsInt, IsOptional, IsString, Min } from 'class-validator';

export class CreateDocumentDto {
  @IsString()
  title: string;

  @IsInt()
  @Min(1)
  pageCount: number;

  @IsOptional()
  @IsString()
  mimeType?: string;

  /** Dung lượng file (byte) — để Web Admin thống kê dung lượng lưu trữ. */
  @IsOptional()
  @IsInt()
  @Min(0)
  fileSize?: number;
}
