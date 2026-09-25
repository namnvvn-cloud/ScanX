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
}
